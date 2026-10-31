package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;
import static com.inqwise.leader.HttpRaftTransportTest.clientOptions;
import static com.inqwise.leader.HttpRaftTransportTest.serverOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

class VertxMembershipTest {
	@TempDir Path directory;
	final List<Local> resources = new ArrayList<>();
	final AtomicBoolean isolateB = new AtomicBoolean();
	record Local(String id, Vertx vertx, VertxRaftStore store, RaftBootstrap bootstrap,
		HttpRaftTransport http, NodeEndpoint endpoint, AtomicReference<VertxLeaderNode> node) {}

	@AfterEach void shutdown() throws Exception {
		for (var local : resources) if (local.node().get() != null) await(local.node().get().close());
		for (var local : resources) await(local.http().close());
		for (var local : resources) await(local.store().close());
		for (var local : resources) await(local.vertx().close());
	}

	Local local(String id) throws Exception {
		var vertx = Vertx.vertx();
		var store = VertxRaftStore.create(vertx, directory.resolve(id), "group", id);
		var bootstrap = new RaftBootstrap(store, "node-a", 1);
		var http = await(HttpRaftTransport.forBootstrap(bootstrap, serverOptions(id), clientOptions(id)));
		var node = new AtomicReference<VertxLeaderNode>();
		int port = await(http.listen(envelope -> node.get() == null ? Future.failedFuture("Not started") : node.get().receive(envelope)));
		var local = new Local(id, vertx, store, bootstrap, http, new NodeEndpoint("127.0.0.1", port, id), node);
		resources.add(local); return local;
	}

	void start(Local local) throws Exception {
		RaftTransport network = new RaftTransport() {
			@Override public Future<Void> send(RaftElection.Envelope envelope) {
				if (isolateB.get() && (envelope.from().equals("node-b") || envelope.to().equals("node-b"))) {
					return Future.succeededFuture();
				}
				return local.http().send(envelope);
			}
			@Override public Future<Void> validateBinding(RaftIdentity identity, RaftGenesis genesis, boolean learner) {
				return local.http().validateBinding(identity, genesis, learner);
			}
		};
		var node = await(VertxLeaderNode.fromStore(local.store(), new RaftElection.Timing(1000, 1800, 100),
			new Random(resources.indexOf(local) + 1), network));
		node.exceptionHandler(cause -> {}); local.node().set(node); await(node.start());
	}

	<T> T eventually(Vertx vertx, Supplier<Future<T>> read, Predicate<T> condition) throws Exception {
		Promise<T> result = Promise.promise();
		long timer = vertx.setPeriodic(15, id -> read.get().onComplete(value -> {
			if (value.failed()) result.tryFail(value.cause());
			else if (condition.test(value.result())) result.tryComplete(value.result());
		}));
		try { return await(result.future()); } finally { vertx.cancelTimer(timer); }
	}

	void caughtUp(Local leader, String learner) throws Exception {
		eventually(leader.vertx(), leader.node().get()::replication, snapshot ->
			snapshot.matchIndex().getOrDefault(learner, 0L) >= snapshot.durableState().commitIndex()
				&& snapshot.durableState().lastIndex() > 0);
	}

	@Test void learnersPromoteWithDualQuorumsLeaderLeavesAndAllRolesRecoverOverHttp() throws Exception {
		var a = local("node-a"); var b = local("node-b"); var c = local("node-c");
		var locals = List.of(a, b, c);
		var endpoints = locals.stream().map(Local::endpoint).toList();
		var genesis = await(a.http().bootstrap(endpoints)).genesis();
		for (var learner : List.of(b, c)) await(learner.store().installLearnerGenesis(genesis));
		for (var local : locals) { await(local.http().discover(endpoints)); start(local); }
		eventually(a.vertx(), a.node().get()::leadership, Leadership::leader);
		assertFalse(await(b.node().get().leadership()).leader());
		assertEquals(0, await(b.node().get().snapshot()).hardState().currentTerm());
		await(a.node().get().addLearner("node-b")); caughtUp(a, "node-b");
		await(a.node().get().addLearner("node-c")); caughtUp(a, "node-c");
		assertEquals(Set.of("node-a"), await(a.node().get().membership()).effective().voters());
		assertEquals(Set.of("node-a", "node-b", "node-c"),
			await(a.node().get().changeVoters(Set.of("node-a", "node-b", "node-c"))).voters());
		eventually(a.vertx(), a.node().get()::leadership, Leadership::leader);
		await(a.node().get().propose("before-leave"));
		isolateB.set(true);
		var leaving = a.node().get().changeVoters(Set.of("node-b", "node-c"));
		eventually(a.vertx(), a.node().get()::membership, membership -> membership.effective().joint());
		assertFalse(await(a.node().get().leadership()).leader(), "self + c is not a new-set majority");
		assertFalse(leaving.isComplete(), "readiness loss must not discard an in-progress membership operation");
		isolateB.set(false);
		assertEquals(Set.of("node-b", "node-c"), await(leaving).voters());
		assertFalse(await(a.node().get().leadership()).leader());
		assertEquals(RaftElection.Role.FOLLOWER, await(a.node().get().snapshot()).role());
		for (var local : locals) { await(local.node().get().close()); await(local.http().close()); }

		var restoredA = local("node-a"); var restoredB = local("node-b"); var restoredC = local("node-c");
		var restored = List.of(restoredA, restoredB, restoredC);
		var restoredEndpoints = restored.stream().map(Local::endpoint).toList();
		for (var local : restored) { await(local.http().discover(restoredEndpoints)); start(local); }
		long removedTerm = await(restoredA.node().get().snapshot()).hardState().currentTerm();
		var elected = eventually(restoredB.vertx(), () -> Future.all(restoredB.node().get().leadership(),
			restoredC.node().get().leadership()).map(values -> ((Leadership) values.resultAt(0)).leader()
				? restoredB : ((Leadership) values.resultAt(1)).leader() ? restoredC : null), value -> value != null);
		assertEquals("after-restart", await(elected.node().get().propose("after-restart")).data());
		assertEquals(Set.of("node-b", "node-c"), await(elected.node().get().membership()).effective().voters());
		assertTrue(await(restoredB.store().bootstrapState()).learnerAdmission(), "admission metadata is not current role");
		assertEquals(genesis, await(elected.store().bootstrapState()).genesis());
		assertEquals(removedTerm, await(restoredA.node().get().snapshot()).hardState().currentTerm());
		assertThrows(Exception.class, () -> await(restoredA.node().get().propose("removed")));
	}
	@Test void learnerCatchesUpFromCompactedLeaderPromotesAndRestartsOverHttp() throws Exception {
		var a = local("node-a"); var b = local("node-b");
		var endpoints = List.of(a.endpoint(), b.endpoint());
		var genesis = await(a.http().bootstrap(endpoints)).genesis();
		await(b.store().installLearnerGenesis(genesis));
		for (var local : List.of(a, b)) await(local.http().discover(endpoints));
		start(a); eventually(a.vertx(), a.node().get()::leadership, Leadership::leader);
		await(a.node().get().addLearner("node-b"));
		var checkpoint = await(a.node().get().compact());
		assertTrue(checkpoint.configuration().learners().contains("node-b"));
		start(b); caughtUp(a, "node-b");
		eventually(b.vertx(), b.node().get()::replication, value -> value.durableState().baseIndex() == checkpoint.lastIncludedIndex());
		assertEquals(checkpoint, await(b.store().persistentState()).snapshot());
		var entry = await(a.node().get().propose("after-snapshot"));
		eventually(b.vertx(), b.node().get()::replication, value -> value.durableState().commitIndex() >= entry.index());
		assertEquals(entry, await(b.store().persistentState()).entryAt(entry.index()));
		await(a.node().get().changeVoters(Set.of("node-a", "node-b")));
		for (var local : List.of(a, b)) { await(local.node().get().close()); await(local.http().close()); }
		var restoredA = local("node-a"); var restoredB = local("node-b");
		var restoredEndpoints = List.of(restoredA.endpoint(), restoredB.endpoint());
		for (var local : List.of(restoredA, restoredB)) { await(local.http().discover(restoredEndpoints)); start(local); }
		var leader = eventually(restoredA.vertx(), () -> Future.all(restoredA.node().get().leadership(),
			restoredB.node().get().leadership()).map(values -> ((Leadership) values.resultAt(0)).leader()
				? restoredA : ((Leadership) values.resultAt(1)).leader() ? restoredB : null), value -> value != null);
		assertTrue(await(leader.node().get().propose("after-snapshot-restart")).index() > entry.index());
		assertEquals(Set.of("node-a", "node-b"), await(leader.node().get().membership()).committed().voters());
		assertEquals(genesis, await(leader.store().bootstrapState()).genesis());
	}

}
