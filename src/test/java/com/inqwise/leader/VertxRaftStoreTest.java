package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

class VertxRaftStoreTest {
	@TempDir Path directory;
	Vertx vertx;

	@BeforeEach void setup() { vertx = Vertx.vertx(); }
	@AfterEach void shutdown() throws Exception { await(vertx.close()); }

	static <T> T await(Future<T> future) throws Exception {
		return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
	}

	VertxRaftStore store() { return VertxRaftStore.create(vertx, directory, "group", "node-a"); }

	@Test void asynchronousWritesRecoverThroughBothImplementations() throws Exception {
		var store = store();
		await(store.open());
		await(store.saveHardState(new RaftHardState(2, "node-a")));
		await(store.appendEntries(List.of(new RaftLogEntry(1, 1, RaftLogEntry.Kind.DATA, "retained"),
			new RaftLogEntry(2, 2, RaftLogEntry.Kind.DATA, "discarded"))));
		await(store.truncateSuffix(2));
		var expected = await(store.persistentState());
		await(store.close());
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			assertEquals(expected, recovered.persistentState());
			recovered.saveHardState(new RaftHardState(3, null));
		}
		var recovered = store();
		await(recovered.open());
		assertEquals(3, await(recovered.persistentState()).hardState().currentTerm());
		await(recovered.close());
	}

	@Test void serializedWritesAndCloseWaitForDurabilityWithoutBlockingEventLoop() throws Exception {
		Promise<Void> gate = Promise.promise();
		CountDownLatch reached = new CountDownLatch(1);
		AtomicBoolean delay = new AtomicBoolean();
		var store = new VertxRaftStore(vertx.getOrCreateContext(), directory, "group", "node-a", 5000, stage -> {
			if (delay.get() && stage == VertxRaftStore.WriteStage.STAGED) {
				reached.countDown();
				return gate.future();
			}
			return Future.succeededFuture();
		});
		await(store.open());
		delay.set(true);
		var first = store.saveHardState(new RaftHardState(1, "node-a"));
		assertTrue(reached.await(5, TimeUnit.SECONDS));
		var second = store.saveHardState(new RaftHardState(2, null));
		var closing = store.close();
		CountDownLatch tick = new CountDownLatch(1);
		store.context().runOnContext(v -> tick.countDown());
		assertTrue(tick.await(5, TimeUnit.SECONDS));
		assertFalse(first.isComplete());
		assertFalse(second.isComplete());
		assertFalse(closing.isComplete());
		assertThrows(IOException.class, () -> FileRaftStore.open(directory, "group", "node-a"));
		gate.complete();
		await(first);
		await(second);
		await(closing);
		await(store.close());
		assertThrows(Exception.class, () -> await(store.identity()));
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			assertEquals(2, recovered.hardState().currentTerm());
		}
	}

	@Test void completedFutureCallbacksRemainOnOwnerContext() throws Exception {
		var store = store();
		var opened = store.open();
		await(opened);
		CountDownLatch callback = new CountDownLatch(1);
		AtomicReference<Object> actual = new AtomicReference<>();
		opened.onComplete(result -> { actual.set(Vertx.currentContext()); callback.countDown(); });
		assertTrue(callback.await(5, TimeUnit.SECONDS));
		assertSame(store.context(), actual.get());
		await(store.close());
	}

	@Test void invalidTransitionsDoNotPoisonStore() throws Exception {
		var store = store();
		await(store.open());
		await(store.saveHardState(new RaftHardState(2, "node-a")));
		assertThrows(Exception.class, () -> await(store.saveHardState(new RaftHardState(1, null))));
		await(store.saveHardState(new RaftHardState(3, null)));
		assertEquals(3, await(store.persistentState()).hardState().currentTerm());
		await(store.close());
	}

	@Test void duplicateOwnershipDoesNotReleaseFirstOwner() throws Exception {
		var first = store();
		await(first.open());
		var second = store();
		assertThrows(Exception.class, () -> await(second.open()));
		await(second.close());
		assertThrows(IOException.class, () -> FileRaftStore.open(directory, "group", "node-a"));
		await(first.saveHardState(new RaftHardState(1, null)));
		await(first.close());
		var third = store();
		await(third.open());
		await(third.close());
	}

	@Test void closeCancelsOpeningAndReleasesOwnership() throws Exception {
		Promise<Void> gate = Promise.promise();
		CountDownLatch reached = new CountDownLatch(1);
		var store = new VertxRaftStore(vertx.getOrCreateContext(), directory, "group", "node-a", 5000, stage -> {
			if (stage == VertxRaftStore.WriteStage.STAGED) { reached.countDown(); return gate.future(); }
			return Future.succeededFuture();
		});
		var opening = store.open();
		assertTrue(reached.await(5, TimeUnit.SECONDS));
		var closing = store.close();
		gate.complete();
		assertThrows(Exception.class, () -> await(opening));
		await(closing);
		var recovered = store();
		await(recovered.open());
		await(recovered.close());
	}

	@ParameterizedTest @EnumSource(VertxRaftStore.WriteStage.class)
	void failedReplacementRecoversOneCompleteState(VertxRaftStore.WriteStage boundary) throws Exception {
		var before = new RaftPersistentState(new RaftHardState(1, null), List.of(), 0);
		try (var baseline = FileRaftStore.open(directory, "group", "node-a")) {
			baseline.savePersistentState(before);
		}
		var after = new RaftPersistentState(new RaftHardState(2, "node-a"), List.of(), 0);
		var store = new VertxRaftStore(vertx.getOrCreateContext(), directory, "group", "node-a", 5000,
			stage -> stage == boundary ? Future.failedFuture(new IOException("injected")) : Future.succeededFuture());
		await(store.open());
		assertThrows(Exception.class, () -> await(store.savePersistentState(after)));
		assertThrows(Exception.class, () -> await(store.persistentState()));
		await(store.close());
		try (var baseline = FileRaftStore.open(directory, "group", "node-a")) {
			assertEquals(boundary == VertxRaftStore.WriteStage.STAGED ? before : after, baseline.persistentState());
		}
	}
	private Process lockHolder() throws Exception {
		String javaCommand = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		Process holder = new ProcessBuilder(javaCommand, "-cp", classpath, LockHolder.class.getName(),
			directory.resolve("store.lock").toString()).redirectErrorStream(true).start();
		var reader = new java.io.BufferedReader(new java.io.InputStreamReader(holder.getInputStream()));
		try {
			assertEquals("locked", vertx.executeBlocking(() -> {
				try { return reader.readLine(); } catch (IOException e) { throw new RuntimeException(e); }
			}).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
			return holder;
		} catch (Throwable e) { holder.destroyForcibly(); throw e; }
	}

	public static class LockHolder {
		public static void main(String[] args) throws Exception {
			try (var channel = java.nio.channels.FileChannel.open(Path.of(args[0]),
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
				var lock = channel.lock()) {
				System.out.println("locked");
				System.out.flush();
				System.in.read();
			}
		}
	}

	@Test void nativeLockTimeoutClosesChannelAndAllowsRecovery() throws Exception {
		Process holder = lockHolder();
		var store = new VertxRaftStore(vertx.getOrCreateContext(), directory, "group", "node-a", 150,
			stage -> Future.succeededFuture());
		try {
			assertThrows(Exception.class, () -> await(store.open()));
			await(store.close());
		} finally {
			holder.getOutputStream().close();
			assertTrue(holder.waitFor(5, TimeUnit.SECONDS));
			holder.destroyForcibly();
		}
		var recovered = store();
		await(recovered.open());
		await(recovered.close());
	}

	@Test void closeAbortsPendingNativeLockAcquisition() throws Exception {
		Process holder = lockHolder();
		var store = store();
		try {
			var opening = store.open();
			Promise<Void> elapsed = Promise.promise();
			vertx.setTimer(150, id -> elapsed.complete());
			await(elapsed.future());
			assertFalse(opening.isComplete());
			store.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
			assertThrows(Exception.class, () -> await(opening));
		} finally {
			holder.getOutputStream().close();
			assertTrue(holder.waitFor(5, TimeUnit.SECONDS));
			holder.destroyForcibly();
		}
		var recovered = store();
		await(recovered.open());
		await(recovered.close());
	}

}
