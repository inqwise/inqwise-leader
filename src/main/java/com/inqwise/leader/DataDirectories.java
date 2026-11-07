package com.inqwise.leader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.file.FileSystem;

/** Selects automatic storage without moving files or silently abandoning an existing identity. */
final class DataDirectories {
	private static final List<String> STORE_FILES = List.of("raft-state.bin", "initialized", "store.lock", "raft-state.pending");
	private DataDirectories() {}

	static Future<Path> resolve(Context context, LeaderOptions options) {
		return resolve(context, options, options.defaultGroupDirectory());
	}

	// The explicit group path permits isolated filesystem tests without changing the application's working directory.
	static Future<Path> resolve(Context context, LeaderOptions options, Path groupDirectory) {
		if (options.getDataDirectory() != null) return Future.succeededFuture(options.resolvedDataDirectory());
		Path group = groupDirectory.toAbsolutePath().normalize();
		FileSystem files = context.owner().fileSystem();
		return legacyIdentity(context, group, options.getGroupName()).compose(legacy -> {
			String id = options.getNodeId();
			if (legacy != null && (id == null || id.equals(legacy.nodeId()))) {
				return files.exists(LeaderOptions.nodeDirectory(group, legacy.nodeId()).toString()).compose(duplicate -> {
					if (duplicate) throw new IllegalStateException(
						"Both legacy and node-specific directories exist; select dataDirectory explicitly");
					return Future.succeededFuture(group);
				});
			}
			if (id != null) return Future.succeededFuture(LeaderOptions.nodeDirectory(group, id));
			Path named = group.resolve("nodes");
			return files.exists(named.toString()).compose(exists -> !exists ? Future.succeededFuture(group)
				: files.readDir(named.toString()).compose(entries -> findNamed(files, entries, 0, new ArrayList<>()))
					.compose(candidates -> switch (candidates.size()) {
						case 0 -> Future.succeededFuture(group);
						case 1 -> Future.succeededFuture(candidates.getFirst());
						default -> throw new IllegalStateException("Several node directories exist; configure nodeId to select one");
					}));
		});
	}

	private static Future<RaftIdentity> legacyIdentity(Context context, Path group, String groupName) {
		FileSystem files = context.owner().fileSystem();
		Path state = group.resolve("raft-state.bin");
		return files.exists(state.toString()).compose(saved -> {
			if (!saved) return hasStoreFiles(files, group, 0).compose(occupied -> {
				if (occupied) throw new IllegalStateException("Legacy directory has incomplete state; recover it explicitly: " + group);
				return Future.succeededFuture();
			});
			Path initialized = group.resolve("initialized");
			return files.props(initialized.toString()).compose(props -> {
				if (props.size() != RaftStoreFormat.INITIALIZED.length) throw new IllegalStateException("Invalid legacy initialization marker");
				return files.readFile(initialized.toString());
			}).compose(marker -> {
				if (!Arrays.equals(marker.getBytes(), RaftStoreFormat.INITIALIZED)) {
					throw new IllegalStateException("Invalid legacy initialization marker");
				}
				return files.props(state.toString());
			}).compose(props -> {
				if (props.size() > RaftStoreFormat.MAX_STATE_BYTES) throw new IllegalStateException("Oversized legacy state");
				return files.readFile(state.toString());
			}).compose(bytes -> context.executeBlocking(() -> {
				var recovered = RaftStoreFormat.decode(bytes.getBytes()).identity();
				if (!recovered.groupName().equals(groupName)) throw new IOException("Legacy directory belongs to another group");
				return recovered;
			}, false));
		});
	}

	private static Future<Boolean> hasStoreFiles(FileSystem files, Path directory, int index) {
		if (index == STORE_FILES.size()) return Future.succeededFuture(false);
		return files.exists(directory.resolve(STORE_FILES.get(index)).toString())
			.compose(exists -> exists ? Future.succeededFuture(true) : hasStoreFiles(files, directory, index + 1));
	}

	private static Future<List<Path>> findNamed(FileSystem files, List<String> entries, int index, List<Path> found) {
		if (index == entries.size() || found.size() > 1) return Future.succeededFuture(found);
		Path candidate = Path.of(entries.get(index));
		if (!candidate.getFileName().toString().matches("[a-f0-9]{64}")) {
			return findNamed(files, entries, index + 1, found);
		}
		return files.props(candidate.toString()).compose(props -> props.isDirectory()
			? hasStoreFiles(files, candidate, 0) : Future.succeededFuture(false)).compose(occupied -> {
			if (occupied) found.add(candidate);
			return findNamed(files, entries, index + 1, found);
		});
	}
}
