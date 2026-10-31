package com.inqwise.leader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

import com.inqwise.leader.RaftStoreFormat.StoredState;

/**
 * Test-only blocking persistence baseline with exclusive directory ownership.
 * Requires a filesystem supporting atomic file replacement, file synchronization, and directory synchronization.
 * Used by storage fault tests and separate-JVM crash fixtures; production uses VertxRaftStore.
 */
final class FileRaftStore implements AutoCloseable {
	// Package-private durability boundary observer for deterministic storage fault tests.
	enum WriteStage { STAGED, REPLACED, DURABLE }
	@FunctionalInterface
	interface WriteObserver {
		void after(WriteStage stage) throws IOException;
	}
	private static final WriteObserver NO_FAULTS = stage -> {};

	private final Path directory;
	private final FileChannel lockChannel;
	private final FileLock lock;
	private final RaftIdentity identity;
	private final WriteObserver writeObserver;
	private RaftPersistentState state;
	private RaftBootstrapState bootstrap;
	private boolean failed;
	private boolean closed;

	private FileRaftStore(Path directory, FileChannel lockChannel, FileLock lock, StoredState state,
		WriteObserver writeObserver) {
		this.directory = directory;
		this.lockChannel = lockChannel;
		this.lock = lock;
		identity = state.identity();
		this.writeObserver = writeObserver;
		this.state = state.state();
		this.bootstrap = state.bootstrap();
	}

	/**
	 * Opens the store, generating a UUID on first initialization.
	 * @param directory private persistent directory
	 * @param groupName consensus group
	 * @return exclusively owned store
	 * @throws IOException if ownership, existing state, or durability cannot be verified
	 */
	public static FileRaftStore open(Path directory, String groupName) throws IOException {
		return open(directory, groupName, null);
	}

	/**
	 * Opens a store, optionally assigning an explicit identity only on first initialization.
	 * Existing group and identity bindings must match the requested configuration.
	 *
	 * @param directory private persistent directory
	 * @param groupName consensus group
	 * @param configuredNodeId initial or expected identity, or {@code null} for a generated/recovered identity
	 * @return exclusively owned store
	 * @throws IOException if ownership, existing state, or durability cannot be verified
	 */
	public static FileRaftStore open(Path directory, String groupName, String configuredNodeId) throws IOException {
		return open(directory, groupName, configuredNodeId, NO_FAULTS);
	}

	static FileRaftStore open(Path directory, String groupName, String configuredNodeId,
		WriteObserver writeObserver) throws IOException {
		Objects.requireNonNull(directory, "directory");
		Objects.requireNonNull(writeObserver, "writeObserver");
		RaftIdentity requested = new RaftIdentity(
			configuredNodeId == null ? UUID.randomUUID().toString() : configuredNodeId, groupName);
		Path requestedDirectory = directory.toAbsolutePath().normalize();
		createDirectory(requestedDirectory);
		Path absolute = requestedDirectory.toRealPath();
		if (!StoreOwnership.claim(absolute)) {
			throw new IOException("Raft directory is already in use: " + absolute);
		}
		FileChannel channel = null;
		try {
			channel = FileChannel.open(absolute.resolve("store.lock"),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			FileLock lock;
			try {
				lock = channel.tryLock();
			} catch (OverlappingFileLockException e) {
				throw new IOException("Raft directory is already in use: " + absolute, e);
			}
			if (lock == null) {
				throw new IOException("Raft directory is already in use: " + absolute);
			}
			Path statePath = absolute.resolve("raft-state.bin");
			Path marker = absolute.resolve("initialized");
			boolean hasState = Files.exists(statePath);
			boolean hasMarker = Files.exists(marker);
			StoredState stored;
			if (hasState != hasMarker) {
				throw new IOException("Incomplete Raft initialization or missing durable state in " + absolute);
			} else if (hasState) {
				if (!Arrays.equals(readBounded(marker), RaftStoreFormat.INITIALIZED)) {
					throw new IOException("Invalid Raft initialization marker");
				}
				stored = RaftStoreFormat.decode(readBounded(statePath));
				if (!stored.identity().groupName().equals(groupName)
					|| (configuredNodeId != null && !stored.identity().nodeId().equals(configuredNodeId))) {
					throw new IOException("Existing Raft identity or group does not match configuration");
				}
			} else {
				// A durable marker prevents missing state from being mistaken for a new node.
				writeSynced(marker, RaftStoreFormat.INITIALIZED, true);
				forceDirectory(absolute);
				stored = new StoredState(requested, new RaftPersistentState(new RaftHardState(0, null), List.of(), 0));
				writeState(absolute, stored, writeObserver);
			}
			return new FileRaftStore(absolute, channel, lock, stored, writeObserver);
		} catch (IOException | RuntimeException e) {
			try {
				if (channel != null) {
					channel.close();
				}
			} catch (IOException closeFailure) {
				e.addSuppressed(closeFailure);
			} finally {
				if (channel == null || !channel.isOpen()) {
					StoreOwnership.release(absolute);
				}
			}
			throw e;
		}
	}

	public synchronized RaftIdentity identity() throws IOException {
		ensureAvailable();
		return identity;
	}

	public synchronized RaftHardState hardState() throws IOException {
		ensureAvailable();
		return state.hardState();
	}

	public synchronized RaftPersistentState persistentState() throws IOException {
		ensureAvailable();
		return state;
	}

	public synchronized RaftBootstrapState bootstrapState() throws IOException {
		ensureAvailable(); return bootstrap;
	}

	public synchronized RaftGenesis createGenesis(java.util.Set<String> eligible, int target) throws IOException {
		ensureAvailable();
		var genesis = RaftStoreFormat.form(identity, state, bootstrap, eligible, target);
		if (bootstrap.genesis() == null) saveBootstrap(new RaftBootstrapState(genesis, false));
		return genesis;
	}

	public synchronized void installGenesis(RaftGenesis genesis) throws IOException {
		ensureAvailable();
		saveBootstrap(RaftStoreFormat.install(identity, state, bootstrap, genesis));
	}

	public synchronized void installLearnerGenesis(RaftGenesis genesis) throws IOException {
		ensureAvailable();
		saveBootstrap(RaftStoreFormat.admitLearner(identity, state, bootstrap, genesis));
	}

	private void saveBootstrap(RaftBootstrapState next) throws IOException {
		if (next.equals(bootstrap)) return;
		try {
			writeState(directory, new StoredState(identity, state, next), writeObserver);
			bootstrap = next;
		} catch (IOException | RuntimeException e) { failed = true; throw e; }
	}

	public synchronized void saveHardState(RaftHardState next) throws IOException {
		ensureAvailable();
		savePersistentState(new RaftPersistentState(next, state.log(), state.commitIndex(), state.snapshot()));
	}

	public synchronized void appendEntries(List<RaftLogEntry> entries) throws IOException {
		ensureAvailable();
		var log = new ArrayList<>(state.log());
		log.addAll(Objects.requireNonNull(entries, "entries"));
		savePersistentState(new RaftPersistentState(state.hardState(), log, state.commitIndex(), state.snapshot()));
	}

	public synchronized void truncateSuffix(long fromIndex) throws IOException {
		ensureAvailable();
		if (fromIndex <= state.commitIndex() || fromIndex < 1 || fromIndex > state.lastIndex() + 1L) {
			throw new IllegalArgumentException("Only an existing uncommitted suffix may be truncated");
		}
		savePersistentState(new RaftPersistentState(state.hardState(),
			state.log().subList(0, Math.toIntExact(fromIndex - state.baseIndex() - 1)), state.commitIndex(), state.snapshot()));
	}

	public synchronized void savePersistentState(RaftPersistentState next) throws IOException {
		ensureAvailable();
		Objects.requireNonNull(next, "state");
		RaftStoreFormat.validateTransition(state, next);
		RaftStoreFormat.validateSnapshotTransition(state, next, bootstrap);
		RaftStoreFormat.validateProtocolBinding(next, bootstrap);
		if (next.equals(state)) {
			return;
		}
		try {
			writeState(directory, new StoredState(identity, next, bootstrap), writeObserver);
			state = next;
		} catch (IOException | RuntimeException e) {
			failed = true;
			throw e;
		}
	}

	@Override
	public synchronized void close() throws IOException {
		if (!closed) {
			closed = true;
			try {
				lock.release();
			} finally {
				try {
					lockChannel.close();
				} finally {
					if (!lockChannel.isOpen()) {
						StoreOwnership.release(directory);
					}
				}
			}
		}
	}

	private void ensureAvailable() throws IOException {
		if (closed || failed) {
			throw new IOException("Raft store is " + (closed ? "closed" : "failed; close and reopen before recovery"));
		}
	}

	private static void createDirectory(Path directory) throws IOException {
		var missing = new ArrayList<Path>();
		for (Path path = directory; path != null && !Files.exists(path); path = path.getParent()) {
			missing.add(path);
		}
		Files.createDirectories(directory);
		for (int i = missing.size() - 1; i >= 0; i--) {
			forceDirectory(missing.get(i).getParent());
		}
	}

	private static void writeState(Path directory, StoredState state, WriteObserver observer) throws IOException {
		Path pending = directory.resolve("raft-state.pending");
		writeSynced(pending, RaftStoreFormat.encode(state), false);
		observer.after(WriteStage.STAGED);
		Files.move(pending, directory.resolve("raft-state.bin"),
			StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		observer.after(WriteStage.REPLACED);
		forceDirectory(directory);
		observer.after(WriteStage.DURABLE);
	}

	private static void writeSynced(Path path, byte[] bytes, boolean createNew) throws IOException {
		var creation = createNew ? StandardOpenOption.CREATE_NEW : StandardOpenOption.CREATE;
		try (FileChannel channel = FileChannel.open(path, creation,
			StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			channel.force(true);
		}
	}

	private static void forceDirectory(Path directory) throws IOException {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		}
	}

	private static byte[] readBounded(Path path) throws IOException {
		try (var stream = Files.newInputStream(path)) {
			byte[] bytes = stream.readNBytes(RaftStoreFormat.MAX_STATE_BYTES + 1);
			if (bytes.length > RaftStoreFormat.MAX_STATE_BYTES) {
				throw new IOException("Raft state exceeds the supported size");
			}
			return bytes;
		}
	}

}
