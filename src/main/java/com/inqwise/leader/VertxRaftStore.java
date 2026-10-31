package com.inqwise.leader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.AsyncFileLock;
import io.vertx.core.file.CopyOptions;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;

/**
 * Event-loop-owned store using Vert.x asynchronous files and serialized future-based operations.
 * Encoding, canonical-path lookup, and directory synchronization run on ordered Vert.x workers.
 * Uses the shared durable codec and transition validation in RaftStoreFormat.
 */
public final class VertxRaftStore implements AsyncRaftStore {
	private static final int MAX_QUEUED_OPERATIONS = 1024;
	private enum Status { NEW, OPENING, OPEN, FAILED, CLOSED }
	enum WriteStage { STAGED, REPLACED, DURABLE }
	@FunctionalInterface
	interface WriteObserver {
		Future<Void> after(WriteStage stage);
	}

	private final Context context;
	private final FileSystem files;
	private final Path requestedDirectory;
	private final String groupName;
	private final String configuredId;
	private final long lockTimeoutMillis;
	private final WriteObserver observer;
	private final ArrayDeque<Runnable> operations = new ArrayDeque<>();
	private Status status = Status.NEW;
	private boolean busy;
	private boolean closing;
	private boolean claimed;
	private boolean acquiringLock;
	private Path directory;
	private AsyncFile lockFile;
	private AsyncFileLock fileLock;
	private Future<Void> lockFileClose;
	private Future<Void> closeFuture;
	private RaftIdentity identity;
	private RaftPersistentState state;
	private RaftBootstrapState bootstrap = RaftBootstrapState.UNASSIGNED;
	private Throwable failure;

	/**
	 * Creates a store on the current or a new Vert.x event-loop context; performs no I/O yet.
	 * @param vertx runtime
	 * @param directory persistent directory
	 * @param groupName consensus group
	 * @param nodeId optional explicit initial/expected ID
	 * @return unopened asynchronous store
	 */
	public static VertxRaftStore create(Vertx vertx, Path directory, String groupName, String nodeId) {
		return new VertxRaftStore(Objects.requireNonNull(vertx, "vertx").getOrCreateContext(), directory,
			groupName, nodeId, 5000, stage -> Future.succeededFuture());
	}

	VertxRaftStore(Context context, Path directory, String groupName, String nodeId,
		long lockTimeoutMillis, WriteObserver observer) {
		this.context = Objects.requireNonNull(context, "context");
		if (!context.isEventLoopContext() || lockTimeoutMillis <= 0) {
			throw new IllegalArgumentException("An event-loop context and positive lock timeout are required");
		}
		new RaftIdentity(nodeId == null ? "validation" : nodeId, groupName);
		this.files = context.owner().fileSystem();
		this.requestedDirectory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
		this.groupName = groupName;
		this.configuredId = nodeId;
		this.lockTimeoutMillis = lockTimeoutMillis;
		this.observer = Objects.requireNonNull(observer, "observer");
	}

	@Override
	public Context context() {
		return context;
	}

	@Override
	public Future<Void> open() {
		return submit(() -> {
			if (status == Status.OPEN) {
				return Future.succeededFuture();
			}
			if (status != Status.NEW) {
				return Future.failedFuture(new IllegalStateException("Create a new store after failure or close"));
			}
			status = Status.OPENING;
			return initialize().map(ignored -> {
				checkCancellation();
				status = Status.OPEN;
				return (Void) null;
			}).recover(cause -> {
				status = Status.FAILED;
				failure = cause;
				return releaseResources().compose(ignored -> Future.failedFuture(cause), cleanupFailure -> {
					cause.addSuppressed(cleanupFailure);
					return Future.failedFuture(cause);
				});
			});
		});
	}

	@Override
	public Future<RaftIdentity> identity() {
		return submit(() -> {
			ensureOpen();
			return Future.succeededFuture(identity);
		});
	}

	@Override
	public Future<RaftPersistentState> persistentState() {
		return submit(() -> {
			ensureOpen();
			return Future.succeededFuture(state);
		});
	}

	@Override
	public Future<RaftBootstrapState> bootstrapState() {
		return submit(() -> { ensureOpen(); return Future.succeededFuture(bootstrap); });
	}

	@Override
	public Future<RaftGenesis> createGenesis(java.util.Set<String> eligible, int target) {
		var copy = java.util.Set.copyOf(eligible);
		return submit(() -> {
			ensureOpen();
			var previous = bootstrap;
			var durable = state;
			return context.executeBlocking(() -> RaftStoreFormat.form(identity, durable, previous, copy, target), true)
				.compose(genesis -> previous.genesis() != null ? Future.succeededFuture(genesis)
					: saveBootstrap(new RaftBootstrapState(genesis, false)).map(genesis));
		});
	}

	@Override
	public Future<Void> installGenesis(RaftGenesis genesis) {
		return submit(() -> {
			ensureOpen();
			var previous = bootstrap;
			var durable = state;
			return context.executeBlocking(() -> RaftStoreFormat.install(identity, durable, previous, genesis), true)
				.compose(this::saveBootstrap);
		});
	}

	@Override
	public Future<Void> installLearnerGenesis(RaftGenesis genesis) {
		return submit(() -> {
			ensureOpen();
			var previous = bootstrap; var durable = state;
			return context.executeBlocking(() -> RaftStoreFormat.admitLearner(identity, durable, previous, genesis), true)
				.compose(this::saveBootstrap);
		});
	}

	private Future<Void> saveBootstrap(RaftBootstrapState next) {
		if (bootstrap.equals(next)) return Future.succeededFuture();
		var stored = new RaftStoreFormat.StoredState(identity, state, next);
		return context.executeBlocking(() -> Buffer.buffer(RaftStoreFormat.encode(stored)), true)
			.compose(bytes -> replaceState(bytes).onFailure(cause -> { failure = cause; status = Status.FAILED; }))
			.onSuccess(v -> bootstrap = next);
	}

	@Override
	public Future<Void> savePersistentState(RaftPersistentState next) {
		return submit(() -> save(next));
	}

	@Override
	public Future<Void> saveHardState(RaftHardState next) {
		return submit(() -> {
			ensureOpen();
			RaftPersistentState previous = state;
			return context.executeBlocking(() -> new RaftPersistentState(next, previous.log(), previous.commitIndex(), previous.snapshot()), true)
				.compose(this::save);
		});
	}

	@Override
	public Future<Void> appendEntries(List<RaftLogEntry> entries) {
		final List<RaftLogEntry> copy;
		try {
			copy = List.copyOf(entries);
		} catch (Exception e) {
			return ContextDispatch.call(context, () -> Future.failedFuture(e));
		}
		return submit(() -> {
			ensureOpen();
			RaftPersistentState previous = state;
			return context.executeBlocking(() -> {
				var log = new ArrayList<>(previous.log());
				log.addAll(copy);
				return new RaftPersistentState(previous.hardState(), log, previous.commitIndex(), previous.snapshot());
			}, true).compose(this::save);
		});
	}

	@Override
	public Future<Void> truncateSuffix(long fromIndex) {
		return submit(() -> {
			ensureOpen();
			RaftPersistentState previous = state;
			if (fromIndex < 1 || fromIndex <= previous.commitIndex() || fromIndex > previous.lastIndex() + 1L) {
				return Future.failedFuture(new IllegalArgumentException("Only an uncommitted suffix may be removed"));
			}
			return context.executeBlocking(() -> new RaftPersistentState(previous.hardState(),
				previous.log().subList(0, Math.toIntExact(fromIndex - previous.baseIndex() - 1)), previous.commitIndex(), previous.snapshot()), true).compose(this::save);
		});
	}

	@Override
	public Future<Void> close() {
		return ContextDispatch.call(context, () -> {
			if (closeFuture == null) {
				closing = true;
				// Closing the channel aborts a pending asynchronous native lock acquisition.
				if (acquiringLock) {
					closeLockFile();
				}
				Promise<Void> result = ContextDispatch.promise(context);
				closeFuture = result.future();
				enqueue(() -> releaseResources().onSuccess(ignored -> status = Status.CLOSED), result);
			}
			return closeFuture;
		});
	}

	private Future<Void> initialize() {
		checkCancellation();
		return missingDirectories(requestedDirectory).compose(missing -> files.mkdirs(requestedDirectory.toString())
			.compose(ignored -> context.<Void>executeBlocking(() -> {
				for (Path created : missing) {
					syncDirectory(created.getParent());
				}
				return null;
			}, true)))
			.compose(ignored -> context.executeBlocking(requestedDirectory::toRealPath, true))
			.compose(canonical -> {
				checkCancellation();
				directory = canonical;
				if (!StoreOwnership.claim(canonical)) {
					return Future.failedFuture(new IOException("Raft directory is already in use: " + canonical));
				}
				claimed = true;
				return files.open(path("store.lock"), new OpenOptions().setRead(false).setWrite(true).setCreate(true));
			}).compose(file -> {
				lockFile = file;
				checkCancellation();
				acquiringLock = true;
				Future<AsyncFileLock> acquisition = file.lock();
				acquisition.onSuccess(ignored -> {
					if (closing || status == Status.FAILED || status == Status.CLOSED) {
						closeLockFile();
					}
				});
				return acquisition.timeout(lockTimeoutMillis, TimeUnit.MILLISECONDS).andThen(result -> acquiringLock = false);
			}).compose(lock -> {
				fileLock = lock;
				checkCancellation();
				return recoverOrInitialize();
			});
	}

	private Future<List<Path>> missingDirectories(Path path) {
		if (path == null) {
			return Future.succeededFuture(List.of());
		}
		return files.exists(path.toString()).compose(exists -> exists ? Future.succeededFuture(List.of())
			: missingDirectories(path.getParent()).map(parents -> {
				var missing = new ArrayList<>(parents);
				missing.add(path);
				return missing;
			}));
	}

	private Future<Void> recoverOrInitialize() {
		return files.exists(path("initialized")).compose(marker -> files.exists(path("raft-state.bin")).compose(saved -> {
			checkCancellation();
			if (marker != saved) {
				return Future.failedFuture(new IOException("Incomplete initialization or missing durable state"));
			}
			if (saved) {
				return readBounded("initialized").compose(buffer -> {
					if (!Arrays.equals(buffer.getBytes(), RaftStoreFormat.INITIALIZED)) {
						return Future.failedFuture(new IOException("Invalid initialization marker"));
					}
					return readBounded("raft-state.bin");
				}).compose(buffer -> context.executeBlocking(() -> RaftStoreFormat.decode(buffer.getBytes()), true))
					.map(recovered -> {
						if (!recovered.identity().groupName().equals(groupName)
							|| (configuredId != null && !recovered.identity().nodeId().equals(configuredId))) {
							throw new IllegalStateException("Stored identity or group does not match configuration");
						}
						identity = recovered.identity();
						state = recovered.state();
						bootstrap = recovered.bootstrap();
						return (Void) null;
					});
			}
			identity = new RaftIdentity(configuredId == null ? UUID.randomUUID().toString() : configuredId, groupName);
			state = new RaftPersistentState(new RaftHardState(0, null), List.of(), 0);
			return writeFile("initialized", Buffer.buffer(RaftStoreFormat.INITIALIZED), true)
				.compose(ignored -> flushDirectory()).compose(ignored -> encode(state))
				.compose(this::replaceState);
		}));
	}

	private Future<Void> save(RaftPersistentState next) {
		ensureOpen();
		Objects.requireNonNull(next, "state");
		RaftPersistentState previous = state;
		return context.executeBlocking(() -> {
			RaftStoreFormat.validateTransition(previous, next);
			RaftStoreFormat.validateSnapshotTransition(previous, next, bootstrap);
			RaftStoreFormat.validateProtocolBinding(next, bootstrap);
			return previous.equals(next);
		}, true).compose(unchanged -> unchanged ? Future.succeededFuture()
			: encode(next).compose(buffer -> replaceState(buffer).onFailure(cause -> {
				failure = cause;
				status = Status.FAILED;
			})).onSuccess(ignored -> state = next));
	}

	private Future<Buffer> encode(RaftPersistentState next) {
		RaftIdentity stableIdentity = identity;
		RaftBootstrapState stableBootstrap = bootstrap;
		return context.executeBlocking(() -> Buffer.buffer(
			RaftStoreFormat.encode(new RaftStoreFormat.StoredState(stableIdentity, next, stableBootstrap))), true);
	}

	private Future<Void> replaceState(Buffer bytes) {
		return writeFile("raft-state.pending", bytes, false)
			.compose(ignored -> observe(WriteStage.STAGED))
			.compose(ignored -> files.move(path("raft-state.pending"), path("raft-state.bin"),
				new CopyOptions().setAtomicMove(true).setReplaceExisting(true)))
			.compose(ignored -> observe(WriteStage.REPLACED))
			.compose(ignored -> flushDirectory())
			.compose(ignored -> observe(WriteStage.DURABLE));
	}

	private Future<Void> observe(WriteStage stage) {
		return ContextDispatch.call(context, () -> observer.after(stage));
	}

	private Future<Void> writeFile(String name, Buffer bytes, boolean createNew) {
		OpenOptions options = new OpenOptions().setRead(false).setWrite(true).setCreate(!createNew)
			.setCreateNew(createNew).setTruncateExisting(true).setSync(true);
		return withFile(name, options, file -> file.write(bytes, 0).compose(ignored -> file.flush()));
	}

	private Future<Buffer> readBounded(String name) {
		return withFile(name, new OpenOptions().setRead(true).setWrite(false).setCreate(false), file ->
			file.size().compose(size -> {
				if (size > RaftStoreFormat.MAX_STATE_BYTES) {
					return Future.failedFuture(new IOException("Raft state exceeds the supported size"));
				}
				return readFully(file, Buffer.buffer(), Math.toIntExact(size));
			}));
	}

	private Future<Buffer> readFully(AsyncFile file, Buffer buffer, int length) {
		int offset = buffer.length();
		if (offset == length) {
			return Future.succeededFuture(buffer);
		}
		return file.read(buffer, offset, offset, length - offset).compose(read -> {
			if (read.length() == offset) {
				return Future.failedFuture(new IOException("Truncated Raft state"));
			}
			return readFully(file, read, length);
		});
	}

	private <T> Future<T> withFile(String name, OpenOptions options, Function<AsyncFile, Future<T>> operation) {
		return files.open(path(name), options).compose(file -> {
			Future<T> result;
			try {
				result = operation.apply(file);
			} catch (Exception e) {
				result = Future.failedFuture(e);
			}
			return result.transform(outcome -> file.close().transform(closed -> {
				if (outcome.failed()) {
					if (closed.failed()) {
						outcome.cause().addSuppressed(closed.cause());
					}
					return Future.failedFuture(outcome.cause());
				}
				return closed.failed() ? Future.failedFuture(closed.cause()) : Future.succeededFuture(outcome.result());
			}));
		});
	}

	private Future<Void> flushDirectory() {
		Path stableDirectory = directory;
		return context.executeBlocking(() -> {
			syncDirectory(stableDirectory);
			return null;
		}, true);
	}

	private static void syncDirectory(Path directory) throws IOException {
		try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		}
	}

	private String path(String name) {
		return directory.resolve(name).toString();
	}

	private Future<Void> closeLockFile() {
		if (lockFileClose == null) {
			lockFileClose = lockFile == null ? Future.succeededFuture() : lockFile.close();
		}
		return lockFileClose;
	}

	private Future<Void> releaseResources() {
		return closeLockFile().onSuccess(ignored -> {
			fileLock = null;
			if (claimed) {
				StoreOwnership.release(directory);
				claimed = false;
			}
		});
	}

	private void ensureOpen() {
		if (status != Status.OPEN) {
			throw new IllegalStateException("Store is not open: " + status, failure);
		}
	}

	private void checkCancellation() {
		if (closing) {
			throw new CancellationException("Store opening was canceled");
		}
	}

	private <T> Future<T> submit(Supplier<Future<T>> operation) {
		Promise<T> result = ContextDispatch.promise(context);
		context.runOnContext(ignored -> {
			if (closing) {
				result.tryFail(new IllegalStateException("Store is closing or closed"));
			} else if (operations.size() >= MAX_QUEUED_OPERATIONS) {
				result.tryFail(new IllegalStateException("Store operation queue is full"));
			} else {
				enqueue(operation, result);
			}
		});
		return result.future();
	}

	private <T> void enqueue(Supplier<Future<T>> operation, Promise<T> result) {
		operations.addLast(() -> {
			Future<T> work;
			try {
				work = operation.get();
			} catch (Exception e) {
				work = Future.failedFuture(e);
			}
			work.onComplete(outcome -> context.runOnContext(ignored -> {
				result.handle(outcome);
				busy = false;
				pump();
			}));
		});
		pump();
	}

	private void pump() {
		if (!busy && !operations.isEmpty()) {
			busy = true;
			operations.removeFirst().run();
		}
	}
}
