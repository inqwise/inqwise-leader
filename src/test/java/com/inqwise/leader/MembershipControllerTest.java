package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;
import static com.inqwise.leader.HttpRaftTransportTest.clientOptions;
import static com.inqwise.leader.HttpRaftTransportTest.serverOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

class MembershipControllerTest {
	@TempDir Path directory;
	final List<Local> locals = new ArrayList<>();
	final AtomicReference<List<NodeEndpoint>> endpoints = new AtomicReference<>(List.of());
	final AtomicBoolean dnsFailed = new AtomicBoolean();
	final Map<String, java.util.Queue<String>> errors = new java.util.concurrent.ConcurrentHashMap<>();
	final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
	record Local(String id, Vertx vertx, VertxRaftStore store, HttpRaftTransport http,
		MembershipController controller, NodeEndpoint endpoint) {}

	@AfterEach void shutdown() throws Exception {
		for (var local : locals) await(local.controller().close());
		for (var local : locals) await(local.vertx().close());
		assertNull(callbackFailure.get());
	}

	NodeDiscovery discovery() {
		return () -> dnsFailed.get() ? Future.failedFuture(new IOException("DNS temporarily unavailable"))
			: Future.succeededFuture(new NodeDiscovery.Result(endpoints.get(), Map.of(), 50));
	}
	Local local(String id, int pool) throws Exception { return local(id, pool, discovery()); }
	Local local(String id, int pool, NodeDiscovery source) throws Exception {
		return local(id, pool, source, stage -> Future.succeededFuture());
	}
	Local local(String id, int pool, NodeDiscovery source, VertxRaftStore.WriteObserver observer) throws Exception {
		var vertx = Vertx.vertx();
		var store = new VertxRaftStore(vertx.getOrCreateContext(), directory.resolve(id), "group", id, 5000, observer);
		var http = await(HttpRaftTransport.forBootstrap(new RaftBootstrap(store, "node-a"), serverOptions(id), clientOptions(id)));
		int port = await(http.listen());
		var controller = new MembershipController(store, http, source,
			new MembershipController.Options(50, 900, pool, null), new RaftElection.Timing(550, 1000, 80), new Random(locals.size() + 7));
		controller.exceptionHandler(cause -> {
			errors.computeIfAbsent(id, ignored -> new java.util.concurrent.ConcurrentLinkedQueue<>()).add(String.valueOf(cause.getMessage()));
			if (Vertx.currentContext() != store.context()) callbackFailure.compareAndSet(null, new AssertionError("error context"));
		});
		controller.leadershipHandler(value -> {
			if (Vertx.currentContext() != store.context()) callbackFailure.compareAndSet(null, new AssertionError("leadership context"));
		});
		var result = new Local(id, vertx, store, http, controller, new NodeEndpoint("127.0.0.1", port, id));
		locals.add(result);
		var update = new ArrayList<>(endpoints.get()); update.removeIf(old -> id.equals(old.expectedNodeId()));
		update.add(result.endpoint()); endpoints.set(List.copyOf(update));
		return result;
	}

	<T> T eventually(Vertx vertx, Supplier<Future<T>> read, Predicate<T> condition) throws Exception {
		Promise<T> result = Promise.promise();
		long timer = vertx.setPeriodic(25, id -> read.get().onSuccess(value -> {
			try { if (condition.test(value)) result.tryComplete(value); } catch (Exception e) { result.tryFail(e); }
		}));
		try { return await(result.future()); } finally { vertx.cancelTimer(timer); }
	}
	Future<MembershipView> view(Local local) { return local.controller().participant().compose(VertxLeaderNode::membershipView); }
	Local leader(List<Local> candidates) throws Exception {
		return eventually(candidates.getFirst().vertx(), () -> Future.all(candidates.stream().map(this::view).toList()).map(results -> {
			for (int i = 0; i < candidates.size(); i++) if (((MembershipView) results.resultAt(i)).leadership().leader()) return candidates.get(i);
			return (Local) null;
		}), value -> value != null);
	}
	List<Local> cluster(int pool) throws Exception {
		var a = local("node-a", pool); var b = local("node-b", pool); var c = local("node-c", pool);
		var group = List.of(a, b, c); for (var local : group) await(local.controller().start());
		leader(group); return group;
	}
	void waitMillis(Vertx vertx, long millis) throws Exception {
		Promise<Void> elapsed = Promise.promise(); vertx.setTimer(millis, id -> elapsed.complete()); await(elapsed.future());
	}
	void standby(Local candidate, List<Local> voters) throws Exception {
		await(candidate.controller().start());
		eventually(candidate.vertx(), () -> Future.all(voters.stream().map(this::view).toList()).map(results -> {
			for (Object value : results.list()) {
				var view = (MembershipView) value;
				if (view.leadership().leader() && view.membership().committed().learners().contains(candidate.id())
					&& view.matchIndex().getOrDefault(candidate.id(), 0L) >= view.commitIndex()
					&& view.responsiveMembers().contains(candidate.id())) return true;
			}
			return false;
		}), Boolean::booleanValue);
	}

	@Test void automaticallyBootstrapsAdmitsReplacesFailedLeaderAndReadmitsRetainedIdentity() throws Exception {
		var original = cluster(2);
		var d = local("node-d", 2); standby(d, original);
		var former = leader(original); var genesis = await(former.store().bootstrapState()).genesis();
		assertEquals(Set.of("node-a", "node-b", "node-c"), await(view(former)).membership().committed().voters());
		long oldTerm = await(former.store().persistentState()).hardState().currentTerm();
		await(former.controller().close());
		var survivors = new ArrayList<>(original); survivors.remove(former); survivors.add(d);
		var expected = new java.util.HashSet<>(Set.of("node-a", "node-b", "node-c")); expected.remove(former.id()); expected.add("node-d");
		eventually(d.vertx(), () -> Future.all(survivors.stream().map(this::view).toList()).map(results ->
			results.list().stream().map(MembershipView.class::cast).anyMatch(view -> view.leadership().leader()
				&& !view.membership().effective().joint() && view.membership().committed().voters().equals(expected))), Boolean::booleanValue);
		var replacementLeader = leader(survivors);
		assertEquals(genesis, await(replacementLeader.store().bootstrapState()).genesis());
		assertEquals(3, await(view(replacementLeader)).membership().committed().voters().size());
		var returned = local(former.id(), 2); standby(returned, survivors);
		assertTrue(await(returned.store().persistentState()).hardState().currentTerm() >= oldTerm);
		waitMillis(returned.vertx(), 1200);
		assertEquals(expected, await(view(leader(survivors))).membership().committed().voters());
	}

	@Test void quorumLossNeverReplacesVotersEvenWithCaughtUpStandby() throws Exception {
		var original = cluster(2); var d = local("node-d", 2); standby(d, original);
		var remaining = leader(original);
		for (var local : original) if (local != remaining) await(local.controller().close());
		eventually(remaining.vertx(), () -> view(remaining), view -> !view.leadership().leader());
		waitMillis(remaining.vertx(), 2100);
		assertEquals(Set.of("node-a", "node-b", "node-c"), await(view(remaining)).membership().effective().voters());
		assertFalse(await(view(remaining)).membership().effective().joint());
		assertFalse(await(view(d)).leadership().leader());
	}

	@Test void dnsFailurePreservesHealthyVotersAndLearnerPoolIsBounded() throws Exception {
		var original = cluster(1); var d = local("node-d", 1); var e = local("node-e", 1);
		await(d.controller().start()); await(e.controller().start());
		var current = leader(original);
		eventually(current.vertx(), () -> view(current), view -> view.membership().committed().learners().size() == 1);
		dnsFailed.set(true); waitMillis(current.vertx(), 1600);
		var stable = await(view(leader(original)));
		assertEquals(Set.of("node-a", "node-b", "node-c"), stable.membership().committed().voters());
		assertTrue(stable.membership().effective().learners().size() <= 1);
		assertFalse(stable.membership().effective().joint());
	}

	@Test void nonleaderStaleTermAndSpoofedIdentityCannotAuthorizeJoin() throws Exception {
		var original = cluster(2); var current = leader(original); var d = local("node-d", 2);
		var follower = original.stream().filter(local -> local != current).findFirst().orElseThrow();
		long term = await(view(current)).leadership().term();
		var request = new JoinRequest(new RaftIdentity("node-d", "group"), null, d.endpoint(), term);
		var client = d.vertx().createHttpClient(clientOptions("node-d"));
		try {
			for (var target : List.of(follower, current)) {
				var body = target == follower ? request : new JoinRequest(request.identity(), null, request.endpoint(), term + 100);
				var post = await(client.request(new RequestOptions().setSsl(true).setHost(target.endpoint().host())
					.setPort(target.endpoint().port()).setMethod(HttpMethod.POST).setURI("/leader/v1/groups/group/join")));
				assertEquals(409, await(post.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE).send(RaftWireCodec.join(body))).statusCode());
			}
			var spoof = new JoinRequest(new RaftIdentity("node-e", "group"), null,
				new NodeEndpoint("127.0.0.1", d.endpoint().port(), "node-e"), term);
			var post = await(client.request(new RequestOptions().setSsl(true).setHost(current.endpoint().host())
				.setPort(current.endpoint().port()).setMethod(HttpMethod.POST).setURI("/leader/v1/groups/group/join")));
			assertEquals(409, await(post.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE).send(RaftWireCodec.join(spoof))).statusCode());
		} finally { await(client.close()); }
		assertTrue(await(view(current)).membership().effective().learners().isEmpty());
		assertEquals(RaftBootstrapState.UNASSIGNED, await(d.store().bootstrapState()));
	}

	@Test void closeDuringDiscoveryCancelsLateFormationAndPreservesIdentity() throws Exception {
		Promise<NodeDiscovery.Result> gate = Promise.promise(); CountDownLatch queried = new CountDownLatch(1);
		var a = local("node-a", 2, () -> { queried.countDown(); return gate.future(); });
		AtomicInteger activations = new AtomicInteger(); a.controller().leadershipHandler(value -> { if (value.leader()) activations.incrementAndGet(); });
		await(a.controller().start()); assertTrue(queried.await(5, TimeUnit.SECONDS));
		await(a.controller().close());
		gate.complete(new NodeDiscovery.Result(List.of(), Map.of(), 50));
		waitMillis(a.vertx(), 100);
		assertEquals(0, activations.get());
		assertThrows(Exception.class, () -> await(a.controller().participant()));
		try (var recovered = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
			assertEquals(RaftBootstrapState.UNASSIGNED, recovered.bootstrapState());
		}
	}
	@Test void conflictingClustersSuspendAdmissionUntilDiscoveryBecomesUnambiguous() throws Exception {
		var original = cluster(2); var current = leader(original);
		var foreignRuntime = Vertx.vertx();
		var foreignStore = VertxRaftStore.create(foreignRuntime, directory.resolve("foreign"), "group", "node-d");
		await(foreignStore.open());
		await(foreignStore.installGenesis(RaftGenesis.create("foreign-cluster", "group", "node-d", Set.of("node-d"), 1)));
		var foreign = await(HttpRaftTransport.forBootstrap(new RaftBootstrap(foreignStore, "node-d", 1),
			serverOptions("node-d"), clientOptions("node-d")));
		int port = await(foreign.listen());
		var unused = new MembershipController(foreignStore, foreign, NodeDiscovery.staticEndpoints(List.of()));
		var foreignEndpoint = new NodeEndpoint("127.0.0.1", port, "node-d");
		locals.add(new Local("node-d", foreignRuntime, foreignStore, foreign, unused, foreignEndpoint));
		var changed = new ArrayList<>(endpoints.get()); changed.add(foreignEndpoint); endpoints.set(List.copyOf(changed));
		var joining = local("node-e", 2); await(joining.controller().start());
		eventually(current.vertx(), () -> Future.succeededFuture(errors.getOrDefault(current.id(), new java.util.ArrayDeque<>())
			.stream().anyMatch(message -> message.contains("Conflicting cluster"))), Boolean::booleanValue);
		assertThrows(Exception.class, () -> await(joining.http().joinCluster(current.endpoint(), joining.endpoint())));
		assertEquals(RaftBootstrapState.UNASSIGNED, await(joining.store().bootstrapState()));
		assertTrue(await(view(current)).membership().effective().learners().isEmpty());
		endpoints.set(endpoints.get().stream().filter(endpoint -> !endpoint.equals(foreignEndpoint)).toList());
		standby(joining, original);
		assertEquals(Set.of("node-a", "node-b", "node-c"), await(view(leader(original))).membership().effective().voters());
	}

	@Test void followerLeavesThroughLeaderWithReplacementAndRetainedIdentityCanRejoin() throws Exception {
		var original = cluster(2); var d = local("node-d", 2); standby(d, original);
		var current = leader(original);
		var departing = original.stream().filter(local -> local != current).findFirst().orElseThrow();
		var genesis = await(current.store().bootstrapState()).genesis();
		var first = departing.controller().leave(5000); var duplicate = departing.controller().leave(1);
		first.onComplete(result -> {
			if (Vertx.currentContext() != departing.store().context()) callbackFailure.compareAndSet(null, new AssertionError("leave context"));
		});
		await(first); await(duplicate);
		assertThrows(Exception.class, () -> await(departing.controller().participant()));
		var survivors = new ArrayList<>(original); survivors.remove(departing); survivors.add(d);
		var stable = await(view(leader(survivors))).membership().committed();
		assertEquals(3, stable.voters().size()); assertTrue(stable.voters().contains("node-d"));
		assertFalse(stable.members().contains(departing.id())); assertFalse(stable.joint());
		try (var retained = FileRaftStore.open(directory.resolve(departing.id()), "group", departing.id())) {
			assertEquals(departing.id(), retained.identity().nodeId()); assertEquals(genesis, retained.bootstrapState().genesis());
		}
		var restarted = local(departing.id(), 2); standby(restarted, survivors);
		assertTrue(await(view(leader(survivors))).membership().committed().learners().contains(restarted.id()));
	}

	@Test void leaderDrainsApplicationWorkBeforeCommittingItsOwnRemovalAndSuccessorIsElected() throws Exception {
		var original = cluster(2); var d = local("node-d", 2); standby(d, original);
		var departing = leader(original); var participant = await(departing.controller().participant());
		var demoted = new CountDownLatch(1); var positiveAfterDemotion = new AtomicBoolean();
		departing.controller().leadershipHandler(value -> {
			if (!value.leader()) demoted.countDown();
			else if (demoted.getCount() == 0) positiveAfterDemotion.set(true);
		});
		var leaving = departing.controller().leave(5000);
		assertTrue(demoted.await(5, TimeUnit.SECONDS));
		assertThrows(Exception.class, () -> await(participant.propose("draining")));
		await(leaving); assertFalse(await(participant.leadership()).leader()); assertFalse(positiveAfterDemotion.get());
		var survivors = new ArrayList<>(original); survivors.remove(departing); survivors.add(d);
		var successor = leader(survivors); var stable = await(view(successor)).membership().committed();
		assertEquals(3, stable.voters().size()); assertFalse(stable.members().contains(departing.id()));
		assertTrue(await(successor.controller().participant().compose(node -> node.propose("after-graceful-leave"))).index() > 0);
	}

	@Test void voterWithoutStandbyCanLeaveWhileRemainingVotersKeepOriginalTarget() throws Exception {
		var original = cluster(2); var current = leader(original);
		var departing = original.stream().filter(local -> local != current).findFirst().orElseThrow();
		await(departing.controller().leave(5000));
		var survivors = original.stream().filter(local -> local != departing).toList();
		var successor = leader(survivors); var committed = await(view(successor)).membership().committed();
		assertEquals(2, committed.voters().size()); assertFalse(committed.members().contains(departing.id()));
		assertEquals(3, await(successor.store().bootstrapState()).genesis().targetVoterCount());
	}

	@Test void learnerLeavesWithoutChangingVotersOrRejoiningFromStaleDiscovery() throws Exception {
		var original = cluster(2); var d = local("node-d", 2); standby(d, original);
		await(d.controller().leave(5000)); var current = leader(original);
		waitMillis(current.vertx(), 1200);
		var committed = await(view(current)).membership().committed();
		assertEquals(Set.of("node-a", "node-b", "node-c"), committed.voters());
		assertFalse(committed.learners().contains("node-d"));
		assertTrue(endpoints.get().stream().anyMatch(endpoint -> "node-d".equals(endpoint.expectedNodeId())));
	}

	@Test void minorityLeaveTimesOutClosesAndDoesNotPretendToReduceQuorum() throws Exception {
		var original = cluster(2); var departing = leader(original);
		for (var local : original) if (local != departing) await(local.controller().close());
		var previous = await(view(departing)).membership().committed();
		var leave = departing.controller().leave(350);
		assertThrows(Exception.class, () -> await(leave));
		assertThrows(Exception.class, () -> await(departing.controller().participant()));
		try (var retained = FileRaftStore.open(directory.resolve(departing.id()), "group", departing.id())) {
			assertEquals(previous, RaftConfiguration.history(retained.bootstrapState().genesis().voters(),
				retained.persistentState().log(), retained.persistentState().commitIndex(), retained.persistentState().snapshot()).committed());
		}
	}

	@Test void finalVoterCannotLeaveAnEmptyVotingSetAndCloseCancelsPendingLeave() throws Exception {
		var a = local("node-a", 2);
		await(a.store().open());
		await(a.store().installGenesis(RaftGenesis.create("single", "group", "node-a", Set.of("node-a"), 1)));
		await(a.controller().start()); leader(List.of(a));
		assertThrows(Exception.class, () -> await(a.controller().leave(0)));
		assertTrue(await(view(a)).leadership().leader());
		var pending = a.controller().leave(5000);
		var repeated = a.controller().leave(5000);
		eventually(a.vertx(), () -> view(a), value -> !value.leadership().leader());
		await(a.controller().close()); assertThrows(Exception.class, () -> await(pending));
		assertThrows(Exception.class, () -> await(repeated));
		assertSame(pending.cause(), repeated.cause());
		var afterClose = a.controller().leave(5000);
		assertThrows(Exception.class, () -> await(afterClose));
		assertSame(pending.cause(), afterClose.cause());
		try (var retained = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
			assertEquals(Set.of("node-a"), RaftConfiguration.history(Set.of("node-a"), retained.persistentState().log(),
				retained.persistentState().commitIndex(), retained.persistentState().snapshot()).committed().voters());
		}
	}

	@Test void leaveBeforeFormationClosesWithoutCreatingGenesis() throws Exception {
		var a = local("node-a", 2);
		var first = a.controller().start(); var repeated = a.controller().start();
		await(first); await(repeated); await(a.controller().leave(1000));
		try (var retained = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
			assertEquals(RaftBootstrapState.UNASSIGNED, retained.bootstrapState());
		}
	}

	@Test void nonleaderStaleTermForeignClusterAndSpoofedIdentityCannotAuthorizeLeave() throws Exception {
		var original = cluster(2); var current = leader(original);
		var follower = original.stream().filter(local -> local != current).findFirst().orElseThrow();
		var requester = original.stream().filter(local -> local != current && local != follower).findFirst().orElseThrow();
		var genesis = await(current.store().bootstrapState()).genesis(); long term = await(view(current)).leadership().term();
		var client = requester.vertx().createHttpClient(clientOptions(requester.id()));
		try {
			var requests = List.of(new LeaveRequest(new RaftIdentity(requester.id(), "group"), genesis.clusterId(), term + 100),
				new LeaveRequest(new RaftIdentity(current.id(), "group"), genesis.clusterId(), term),
				new LeaveRequest(new RaftIdentity(requester.id(), "group"), "foreign", term),
				new LeaveRequest(new RaftIdentity(requester.id(), "other"), genesis.clusterId(), term));
			for (var request : requests) {
				var post = await(client.request(new RequestOptions().setSsl(true).setHost(current.endpoint().host())
					.setPort(current.endpoint().port()).setMethod(HttpMethod.POST).setURI("/leader/v1/groups/group/leave")));
				assertEquals(409, await(post.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE).send(RaftWireCodec.leave(request))).statusCode());
			}
			var post = await(client.request(new RequestOptions().setSsl(true).setHost(follower.endpoint().host())
				.setPort(follower.endpoint().port()).setMethod(HttpMethod.POST).setURI("/leader/v1/groups/group/leave")));
			assertEquals(409, await(post.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE).send(RaftWireCodec.leave(
				new LeaveRequest(new RaftIdentity(requester.id(), "group"), genesis.clusterId(), term)))).statusCode());
		} finally { await(client.close()); }
		assertEquals(Set.of("node-a", "node-b", "node-c"), await(view(current)).membership().committed().voters());
	}

	@Test void leaveCompletionWaitsForRemovalCommitDurability() throws Exception {
		var armed = new AtomicBoolean(); var writes = new AtomicInteger(); var reached = new CountDownLatch(1);
		var gate = Promise.<Void>promise();
		VertxRaftStore.WriteObserver observer = stage -> {
			if (armed.get() && stage == VertxRaftStore.WriteStage.STAGED && writes.incrementAndGet() == 2) {
				reached.countDown(); return gate.future();
			}
			return Future.succeededFuture();
		};
		var a = local("node-a", 2, discovery(), observer);
		var b = local("node-b", 2, discovery(), observer); var c = local("node-c", 2, discovery(), observer);
		var original = List.of(a, b, c); for (var local : original) await(local.controller().start());
		var current = leader(original); var d = local("node-d", 2); standby(d, original);
		armed.set(true); var leaving = d.controller().leave(5000);
		try {
			assertTrue(reached.await(5, TimeUnit.SECONDS)); assertFalse(leaving.isComplete());
			assertTrue(await(view(current)).membership().committed().learners().contains("node-d"));
		} finally { gate.tryComplete(); }
		await(leaving); assertFalse(await(view(leader(original))).membership().committed().members().contains("node-d"));
	}

	@Test void authenticatedRemovalRetryReturnsCommittedReceiptWithoutAnotherConfigurationEntry() throws Exception {
		var original = cluster(2); var current = leader(original); var d = local("node-d", 2); standby(d, original);
		await(d.controller().participant().compose(VertxLeaderNode::drain)); await(d.http().beginLeave());
		var first = await(d.http().leaveCluster(current.endpoint()));
		assertFalse(first.configuration().members().contains(d.id())); assertFalse(first.configuration().joint());
		assertTrue(first.configurationIndex() <= first.commitIndex());
		var again = await(d.http().leaveCluster(current.endpoint()));
		assertEquals(first.configurationIndex(), again.configurationIndex()); assertEquals(first.configuration(), again.configuration());
		assertThrows(Exception.class, () -> await(d.http().joinCluster(current.endpoint(), d.endpoint())));
		assertTrue(await(current.http().verifyPeer(d.endpoint())).departing());
		await(d.controller().close());
	}

	@Test void stalledUnrelatedPeerAndDnsFailureDoNotConsumeLeaveDeadline() throws Exception {
		var original = cluster(2); var current = leader(original); var d = local("node-d", 2); standby(d, original);
		var genesis = await(current.store().bootstrapState()).genesis(); var extra = Vertx.vertx();
		var stalled = extra.createHttpServer(serverOptions("node-e")).requestHandler(request -> {
			request.response().putHeader("X-Inqwise-Node-Id", "node-e");
			if (request.uri().endsWith("/identity")) {
				try { request.response().putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE).end(
					RaftWireCodec.identity(new RaftIdentity("node-e", "group"), genesis.clusterId()));
				} catch (IOException e) { request.response().setStatusCode(500).end(); }
			} // Deliberately never answer membership probes.
		});
		try {
			int port = await(stalled.listen()).actualPort();
			await(d.http().verifyPeer(new NodeEndpoint("127.0.0.1", port, "node-e")));
			dnsFailed.set(true);
			await(d.controller().leave(2000));
			assertFalse(await(view(leader(original))).membership().committed().members().contains("node-d"));
		} finally { await(stalled.close()); await(extra.close()); }
	}

	@Test void leaveDuringAcceptedBootstrapWriteDrainsStorageWithoutClaimingRemoval() throws Exception {
		var armed = new AtomicBoolean(); var reached = new CountDownLatch(1); var gate = Promise.<Void>promise();
		var a = local("node-a", 2, discovery(), stage -> {
			if (armed.get() && stage == VertxRaftStore.WriteStage.STAGED) { reached.countDown(); return gate.future(); }
			return Future.succeededFuture();
		});
		var b = local("node-b", 2); local("node-c", 2);
		armed.set(true); await(a.controller().start()); assertTrue(reached.await(5, TimeUnit.SECONDS));
		var leaving = a.controller().leave(100);
		try { waitMillis(b.vertx(), 150); assertFalse(leaving.isComplete(), "cleanup must drain the accepted write"); }
		finally { gate.tryComplete(); }
		assertThrows(Exception.class, () -> await(leaving));
		try (var retained = FileRaftStore.open(directory.resolve("node-a"), "group", "node-a")) {
			assertNotNull(retained.bootstrapState().genesis()); assertEquals("node-a", retained.identity().nodeId());
			assertEquals(0, retained.persistentState().commitIndex());
		}
	}

}
