package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxTestContext;

class VertxLeaderNodeTest {
	@TempDir Path directory;
	Vertx vertx;
	List<VertxLeaderNode> nodes;
	final RaftElection.Timing timing = new RaftElection.Timing(400, 850, 60);

	@BeforeEach void setup() { vertx = Vertx.vertx(); nodes = new ArrayList<>(); }
	@AfterEach void shutdown() throws Exception {
		for (var node : nodes) await(node.close());
		await(vertx.close());
	}

	VertxLeaderNode node(String id, Set<String> voters, RaftTransport transport) {
		return node(VertxRaftStore.create(vertx, directory.resolve(id), "group", id), voters, transport);
	}

	VertxLeaderNode node(AsyncRaftStore store, Set<String> voters, RaftTransport transport) {
		var node = new VertxLeaderNode(store, "cluster", voters, timing, new Random(nodes.size() + 1), transport);
		nodes.add(node);
		return node;
	}

	<T> T eventually(java.util.function.Supplier<Future<T>> read, Predicate<T> predicate) throws Exception {
		Promise<T> result = Promise.promise();
		long timer = vertx.setPeriodic(15, id -> read.get().onComplete(value -> {
			if (value.failed()) result.tryFail(value.cause());
			else if (predicate.test(value.result())) result.tryComplete(value.result());
		}));
		try { return await(result.future()); } finally { vertx.cancelTimer(timer); }
	}

	@Test void singleVoterCommitsProposalsAndReplaysAfterRecovery() throws Exception {
		var node = node("node-a", Set.of("node-a"), message -> Future.succeededFuture());
		await(node.start());
		eventually(node::leadership, Leadership::leader);
		var entry = await(node.propose("coordination-data"));
		assertEquals("coordination-data", entry.data());
		await(node.close());
		CountDownLatch replay = new CountDownLatch(1);
		var recovered = node("node-a", Set.of("node-a"), message -> Future.succeededFuture());
		recovered.committedHandler(committed -> { if (committed.equals(entry)) replay.countDown(); });
		await(recovered.start());
		assertTrue(replay.await(5, TimeUnit.SECONDS));
		eventually(recovered::leadership, Leadership::leader);
		assertTrue(await(recovered.snapshot()).hardState().currentTerm() > entry.term());
	}

	@ParameterizedTest @ValueSource(ints = {2, 3})
	void readinessWaitsForDurableCurrentTermCommit(int heldWrite) throws Exception {
		Promise<Void> gate = Promise.promise();
		AtomicBoolean armed = new AtomicBoolean();
		AtomicInteger writes = new AtomicInteger();
		CountDownLatch reached = new CountDownLatch(1);
		var store = new VertxRaftStore(vertx.getOrCreateContext(), directory, "group", "node-a", 5000, stage -> {
			if (armed.get() && stage == VertxRaftStore.WriteStage.STAGED && writes.incrementAndGet() == heldWrite) {
				reached.countDown(); return gate.future();
			}
			return Future.succeededFuture();
		});
		var node = node(store, Set.of("node-a"), message -> Future.succeededFuture());
		await(node.start());
		armed.set(true);
		assertTrue(reached.await(5, TimeUnit.SECONDS));
		assertEquals(RaftElection.Role.LEADER, await(node.snapshot()).role());
		assertFalse(await(node.leadership()).leader());
		assertThrows(Exception.class, () -> await(node.propose("too-early")));
		assertThrows(Exception.class, () -> await(node.fencingToken()));
		gate.complete();
		eventually(node::leadership, Leadership::leader);
	}

	@Test void threeVotersReplicateAndIsolatedLeaderLosesReadiness() throws Exception {
		Map<String, VertxLeaderNode> network = new ConcurrentHashMap<>();
		Set<String> voters = Set.of("node-a", "node-b", "node-c");
		AtomicBoolean partition = new AtomicBoolean();
		RaftTransport transport = message -> {
			if (partition.get()) return Future.succeededFuture();
			var target = network.get(message.to());
			if (target != null) target.receive(message).onFailure(ignored -> {});
			return Future.succeededFuture();
		};
		for (String id : voters) network.put(id, node(id, voters, transport));
		for (var node : network.values()) await(node.start());
		var leader = eventually(() -> Future.all(network.values().stream().map(VertxLeaderNode::leadership).toList())
			.map(results -> {
				int i = 0;
				for (var candidate : network.values()) if (((Leadership) results.resultAt(i++)).leader()) return candidate;
				return (VertxLeaderNode) null;
			}), value -> value != null);
		var entry = eventually(() -> leader.propose("replicated").recover(cause -> Future.succeededFuture(null)),
			value -> value != null);
		assertEquals("replicated", entry.data());
		partition.set(true);
		eventually(leader::leadership, value -> !value.leader());
		assertThrows(Exception.class, () -> await(leader.propose("minority")));
		partition.set(false);
		eventually(() -> Future.all(network.values().stream().map(VertxLeaderNode::leadership).toList())
			.map(result -> result.list().stream().anyMatch(value -> ((Leadership) value).leader())), Boolean::booleanValue);
	}

	@Test void handlersAndLateFutureCallbacksUseOwnerAndSubscriberFailuresAreReported() throws Exception {
		var node = node("node-a", Set.of("node-a"), message -> Future.succeededFuture());
		VertxTestContext checked = new VertxTestContext();
		CountDownLatch reported = new CountDownLatch(1);
		node.exceptionHandler(cause -> {
			checked.verify(() -> assertSame(node.context(), Vertx.currentContext()));
			if (cause.getMessage().equals("subscriber")) reported.countDown();
		});
		node.leadershipHandler(value -> {
			checked.verify(() -> assertSame(node.context(), Vertx.currentContext()));
			if (value.leader()) throw new IllegalStateException("subscriber");
		});
		var started = node.start();
		await(started);
		started.onComplete(value -> {
			checked.verify(() -> assertSame(node.context(), Vertx.currentContext()));
			checked.completeNow();
		});
		assertTrue(checked.awaitCompletion(5, TimeUnit.SECONDS));
		if (checked.failed()) throw new AssertionError(checked.causeOfFailure());
		assertTrue(reported.await(5, TimeUnit.SECONDS));
		assertEquals("still-running", await(node.propose("still-running")).data());
	}

	@Test void closeDuringStartupCancelsLateActivationAndReleasesStore() throws Exception {
		Promise<Void> gate = Promise.promise();
		CountDownLatch reached = new CountDownLatch(1);
		var store = new VertxRaftStore(vertx.getOrCreateContext(), directory, "group", "node-a", 5000, stage -> {
			if (stage == VertxRaftStore.WriteStage.STAGED) { reached.countDown(); return gate.future(); }
			return Future.succeededFuture();
		});
		AtomicInteger activations = new AtomicInteger();
		var node = node(store, Set.of("node-a"), message -> Future.succeededFuture());
		node.leadershipHandler(value -> { if (value.leader()) activations.incrementAndGet(); });
		var starting = node.start();
		assertTrue(reached.await(5, TimeUnit.SECONDS));
		var closing = node.close();
		assertThrows(Exception.class, () -> await(starting));
		gate.complete();
		await(closing);
		await(node.close());
		assertFalse(await(node.leadership()).leader());
		assertEquals(0, activations.get());
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			assertEquals(0, recovered.hardState().currentTerm());
		}
	}

	@Test void failedPersistenceStopsParticipationAndReleasesOwnership() throws Exception {
		AtomicBoolean armed = new AtomicBoolean();
		var store = new VertxRaftStore(vertx.getOrCreateContext(), directory, "group", "node-a", 5000,
			stage -> armed.get() ? Future.failedFuture(new IOException("disk failed")) : Future.succeededFuture());
		var node = node(store, Set.of("node-a"), message -> Future.succeededFuture());
		CountDownLatch error = new CountDownLatch(1);
		node.exceptionHandler(cause -> error.countDown());
		await(node.start());
		armed.set(true);
		assertTrue(error.await(5, TimeUnit.SECONDS));
		await(node.close());
		assertFalse(await(node.leadership()).leader());
		assertThrows(Exception.class, () -> await(node.snapshot()));
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			assertNotNull(recovered.identity());
		}
	}
	@Test void proposalCompletesOnlyAfterCommitWriteIsDurable() throws Exception {
		Promise<Void> gate = Promise.promise();
		AtomicBoolean armed = new AtomicBoolean();
		AtomicInteger writes = new AtomicInteger();
		CountDownLatch reached = new CountDownLatch(1);
		var store = new VertxRaftStore(vertx.getOrCreateContext(), directory, "group", "node-a", 5000, stage -> {
			if (armed.get() && stage == VertxRaftStore.WriteStage.STAGED && writes.incrementAndGet() == 2) {
				reached.countDown(); return gate.future();
			}
			return Future.succeededFuture();
		});
		var node = node(store, Set.of("node-a"), message -> Future.succeededFuture());
		await(node.start());
		eventually(node::leadership, Leadership::leader);
		armed.set(true);
		var proposal = node.propose("durable-commit");
		assertTrue(reached.await(5, TimeUnit.SECONDS));
		assertFalse(proposal.isComplete());
		gate.complete();
		assertEquals("durable-commit", await(proposal).data());
	}

	@Test void closeFailsOutstandingProposalAndDrainsAcceptedWrite() throws Exception {
		Promise<Void> gate = Promise.promise();
		AtomicBoolean armed = new AtomicBoolean();
		CountDownLatch reached = new CountDownLatch(1);
		var store = new VertxRaftStore(vertx.getOrCreateContext(), directory, "group", "node-a", 5000, stage -> {
			if (armed.get() && stage == VertxRaftStore.WriteStage.STAGED) {
				reached.countDown(); return gate.future();
			}
			return Future.succeededFuture();
		});
		var node = node(store, Set.of("node-a"), message -> Future.succeededFuture());
		await(node.start());
		eventually(node::leadership, Leadership::leader);
		armed.set(true);
		var proposal = node.propose("unknown-outcome");
		assertTrue(reached.await(5, TimeUnit.SECONDS));
		var closing = node.close();
		assertThrows(Exception.class, () -> await(proposal));
		assertFalse(closing.isComplete());
		gate.complete();
		await(closing);
		assertThrows(Exception.class, () -> await(node.start()));
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			assertEquals("unknown-outcome", recovered.persistentState().log().getLast().data());
			assertTrue(recovered.persistentState().commitIndex() < recovered.persistentState().log().size());
		}
	}

	@Test void restartRejectsChangedClusterOrVotersBeforeAnyElection() throws Exception {
		var first = node("node-a", Set.of("node-a"), message -> Future.succeededFuture());
		await(first.start());
		eventually(first::leadership, Leadership::leader);
		await(first.close());
		RaftHardState previous;
		try (var baseline = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
			previous = baseline.hardState();
		}
		for (boolean changedCluster : List.of(true, false)) {
			var store = VertxRaftStore.create(vertx, directory.resolve("node-a"), "group", "node-a");
			var invalid = new VertxLeaderNode(store, changedCluster ? "foreign" : "cluster",
				changedCluster ? Set.of("node-a") : Set.of("node-a", "node-b", "node-c"), timing,
				new Random(1), message -> Future.succeededFuture());
			nodes.add(invalid); invalid.exceptionHandler(cause -> {});
			assertThrows(Exception.class, () -> await(invalid.start()));
			await(invalid.close());
			try (var baseline = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
				assertEquals(previous, baseline.hardState());
				assertEquals(Set.of("node-a"), baseline.bootstrapState().genesis().voters());
				assertEquals("cluster", baseline.bootstrapState().genesis().clusterId());
			}
		}
	}

}
