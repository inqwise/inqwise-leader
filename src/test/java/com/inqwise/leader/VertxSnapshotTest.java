package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

class VertxSnapshotTest {
	@TempDir Path directory;
	Vertx vertx;
	List<VertxLeaderNode> nodes = new ArrayList<>();
	static final Set<String> SINGLE = Set.of("node-a");
	static final RaftConfiguration CONFIG = RaftConfiguration.stable(SINGLE, Set.of());
	@BeforeEach void setup() { vertx = Vertx.vertx(); }
	@AfterEach void shutdown() throws Exception {
		for (var node : nodes) await(node.close());
		await(vertx.close());
	}
	VertxLeaderNode node(Set<String> voters, RaftTransport transport) {
		var store = VertxRaftStore.create(vertx, directory, "group", "node-a");
		var node = new VertxLeaderNode(store, "cluster", voters, new RaftElection.Timing(300, 600, 40),
			new Random(42), transport); nodes.add(node); return node;
	}
	void seed(RaftPersistentState state) throws Exception {
		try (var store = FileRaftStore.open(directory, "group", "node-a")) {
			store.installGenesis(RaftGenesis.create("cluster", "group", null, SINGLE, 1)); store.savePersistentState(state);
		}
	}
	<T> T eventually(Supplier<Future<T>> read, Predicate<T> condition) throws Exception {
		var result = Promise.<T>promise();
		long timer = vertx.setPeriodic(10, id -> read.get().onComplete(value -> {
			if (value.failed()) result.tryFail(value.cause());
			else if (condition.test(value.result())) result.tryComplete(value.result());
		}));
		try { return await(result.future()); } finally { vertx.cancelTimer(timer); }
	}

	@Test void automaticMetadataCompactionPreservesReadinessAndCanContinueBeyondOldHistoryLimit() throws Exception {
		var prior = new RaftSnapshot(5000, 2, CONFIG, 0, "");
		var entries = IntStream.rangeClosed(5001, 5130).mapToObj(index ->
			new RaftLogEntry(index, 2, RaftLogEntry.Kind.NO_OP, "")).toList();
		seed(new RaftPersistentState(new RaftHardState(2, null), entries, 5130, prior));
		var node = node(SINGLE, envelope -> Future.succeededFuture()); await(node.start());
		eventually(node::replication, value -> value.durableState().baseIndex() >= 5130);
		eventually(node::leadership, Leadership::leader);
		var proposal = await(node.propose("after-5130")); assertTrue(proposal.index() > 5130);
		assertTrue(await(node.leadership()).leader());
		assertEquals(CONFIG, await(node.membership()).committed());
	}

	@Test void asynchronousProviderKeepsLaterEntriesAndRestartRestoresBeforeTailReplay() throws Exception {
		var node = node(SINGLE, envelope -> Future.succeededFuture());
		var captured = new CountDownLatch(1); var data = Promise.<String>promise(); var boundary = new AtomicLong();
		var delivered = new AtomicLong();
		node.committedHandler(entry -> delivered.set(entry.index()));
		await(node.snapshotHandlers(point -> {
			assertSame(node.context(), Vertx.currentContext());
			assertEquals(point.index(), delivered.get()); boundary.set(point.index()); captured.countDown(); return data.future();
		}, checkpoint -> Future.succeededFuture()));
		await(node.start()); eventually(node::leadership, Leadership::leader);
		var first = await(node.propose("first")); var compacting = node.compact();
		assertTrue(captured.await(5, TimeUnit.SECONDS));
		var second = await(node.propose("second")); assertFalse(compacting.isComplete());
		data.complete("state-at-" + first.index()); var checkpoint = await(compacting);
		assertEquals(boundary.get(), checkpoint.lastIncludedIndex());
		assertEquals(List.of(second), await(node.replication()).durableState().log());
		assertTrue(await(node.leadership()).leader()); await(node.close());

		var restored = node(SINGLE, envelope -> Future.succeededFuture());
		var gate = Promise.<Void>promise(); var restoring = new CountDownLatch(1); var replay = new CountDownLatch(1);
		var events = new CopyOnWriteArrayList<String>();
		restored.committedHandler(entry -> { if (entry.equals(second)) { events.add("tail"); replay.countDown(); } });
		await(restored.snapshotHandlers(point -> Future.succeededFuture("later"), saved -> {
			assertSame(restored.context(), Vertx.currentContext()); assertEquals(checkpoint, saved);
			events.add("restore"); restoring.countDown(); return gate.future();
		}));
		var startup = restored.start(); assertTrue(restoring.await(5, TimeUnit.SECONDS));
		assertFalse(startup.isComplete()); assertFalse(await(restored.leadership()).leader()); assertEquals(List.of("restore"), events);
		gate.complete(); await(startup); assertTrue(replay.await(5, TimeUnit.SECONDS));
		assertEquals(List.of("restore", "tail"), events); eventually(restored::leadership, Leadership::leader);
		assertTrue(await(restored.propose("third")).index() > second.index());
	}

	@Test void dataWithoutHooksCannotBeCompactedAndProviderFailurePreservesLog() throws Exception {
		var node = node(SINGLE, envelope -> Future.succeededFuture()); await(node.start());
		eventually(node::leadership, Leadership::leader); var entry = await(node.propose("retained"));
		var rejected = node.compact(); assertThrows(Exception.class, () -> await(rejected));
		assertInstanceOf(IllegalStateException.class, rejected.cause());
		assertEquals("DATA requires application snapshot hooks", rejected.cause().getMessage());
		var retry = node.compact(); assertThrows(Exception.class, () -> await(retry));
		assertEquals(rejected.cause().getMessage(), retry.cause().getMessage(), "Missing hooks must not retain compaction state");
		assertNull(await(node.replication()).durableState().snapshot()); await(node.close());
		var restarted = node(SINGLE, envelope -> Future.succeededFuture());
		await(restarted.snapshotHandlers(point -> Future.failedFuture("provider failed"), checkpoint -> Future.succeededFuture()));
		await(restarted.start()); eventually(restarted::leadership, Leadership::leader);
		assertThrows(Exception.class, () -> await(restarted.compact()));
		assertEquals(entry, await(restarted.replication()).durableState().entryAt(entry.index()));
		assertTrue(await(restarted.leadership()).leader());
	}

	@Test void snapshotAcknowledgmentWaitsForApplicationRestoreAndDuplicateDoesNotRestoreAgain() throws Exception {
		var sent = new CopyOnWriteArrayList<RaftElection.Message>();
		var node = node(Set.of("node-a", "node-b"), envelope -> { sent.add(envelope.message()); return Future.succeededFuture(); });
		var restoring = new CountDownLatch(1); var gate = Promise.<Void>promise(); var restores = new AtomicLong();
		await(node.snapshotHandlers(point -> Future.succeededFuture(""), snapshot -> {
			restores.incrementAndGet(); restoring.countDown(); return gate.future();
		})); await(node.start());
		var checkpoint = new RaftSnapshot(100, 2,
			RaftConfiguration.stable(Set.of("node-a", "node-b"), Set.of()), 0, "app");
		var envelope = new RaftElection.Envelope("group", "cluster", "node-b", "node-a", new RaftElection.InstallSnapshot(2, 10, checkpoint));
		await(node.receive(envelope)); assertTrue(restoring.await(5, TimeUnit.SECONDS));
		assertEquals(0, await(node.replication()).durableState().baseIndex());
		assertTrue(sent.stream().noneMatch(RaftElection.SnapshotResponse.class::isInstance));
		gate.complete(); eventually(() -> Future.succeededFuture(sent), values -> values.stream().anyMatch(RaftElection.SnapshotResponse.class::isInstance));
		assertEquals(100, await(node.replication()).durableState().baseIndex());
		await(node.receive(envelope)); assertEquals(1, restores.get());
	}

	@Test void closeDemotesImmediatelyAndKeepsOwnershipUntilAcceptedRestoreFinishes() throws Exception {
		seed(new RaftPersistentState(new RaftHardState(2, null), List.of(), 100, new RaftSnapshot(100, 2, CONFIG, 0, "app")));
		var node = node(SINGLE, envelope -> Future.succeededFuture());
		var restoring = new CountDownLatch(1); var gate = Promise.<Void>promise();
		await(node.snapshotHandlers(point -> Future.succeededFuture(""), checkpoint -> { restoring.countDown(); return gate.future(); }));
		var started = node.start(); assertTrue(restoring.await(5, TimeUnit.SECONDS));
		var closing = node.close(); assertThrows(Exception.class, () -> await(started));
		assertFalse(await(node.leadership()).leader()); assertFalse(closing.isComplete());
		assertThrows(Exception.class, () -> FileRaftStore.open(directory, "group", "node-a"));
		gate.complete(); await(closing);
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) { assertEquals(100, recovered.persistentState().baseIndex()); }
	}

	@Test void asynchronousStoreAppendAndTruncationPreserveAbsoluteCheckpoint() throws Exception {
		var checkpoint = new RaftSnapshot(5000, 2, CONFIG, 0, "app");
		seed(new RaftPersistentState(new RaftHardState(2, null), List.of(), 5000, checkpoint));
		var store = VertxRaftStore.create(vertx, directory, "group", "node-a");
		try {
			await(store.open()); await(store.saveHardState(new RaftHardState(3, null)));
			var first = new RaftLogEntry(5001, 3, RaftLogEntry.Kind.DATA, "retained");
			await(store.appendEntries(List.of(first, new RaftLogEntry(5002, 3, RaftLogEntry.Kind.DATA, "discard"))));
			await(store.truncateSuffix(5002));
			assertEquals(checkpoint, await(store.persistentState()).snapshot());
			assertEquals(List.of(first), await(store.persistentState()).log());
			assertThrows(Exception.class, () -> await(store.truncateSuffix(5000)));
		} finally { await(store.close()); }
	}

	@Test void failedRestorationPreventsReadinessAndCanBeRetriedOnRecovery() throws Exception {
		seed(new RaftPersistentState(new RaftHardState(2, null), List.of(), 100, new RaftSnapshot(100, 2, CONFIG, 0, "app")));
		var node = node(SINGLE, envelope -> Future.succeededFuture()); node.exceptionHandler(cause -> {});
		await(node.snapshotHandlers(point -> Future.succeededFuture(""), checkpoint -> Future.failedFuture("restore failed")));
		assertThrows(Exception.class, () -> await(node.start())); assertFalse(await(node.leadership()).leader()); await(node.close());
		var recovered = node(SINGLE, envelope -> Future.succeededFuture());
		await(recovered.snapshotHandlers(point -> Future.succeededFuture("app"), checkpoint -> Future.succeededFuture()));
		await(recovered.start()); eventually(recovered::leadership, Leadership::leader);
	}
}
