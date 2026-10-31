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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

class RaftBootstrapHttpTest {
	@TempDir Path directory;
	final List<Local> locals = new ArrayList<>();
	record Local(String id, Vertx vertx, VertxRaftStore store, RaftBootstrap bootstrap,
		HttpRaftTransport transport, NodeEndpoint endpoint, AtomicReference<VertxLeaderNode> node) {}

	@AfterEach void shutdown() throws Exception {
		for (var local : locals) {
			if (local.node().get() != null) await(local.node().get().close());
			await(local.transport().close()); await(local.store().close()); await(local.vertx().close());
		}
	}

	Local local(String id) throws Exception { return local(id, null); }
	Local local(String id, VertxRaftStore.WriteObserver observer) throws Exception {
		Vertx vertx = Vertx.vertx();
		var store = observer == null ? VertxRaftStore.create(vertx, directory.resolve(id), "group", id)
			: new VertxRaftStore(vertx.getOrCreateContext(), directory.resolve(id), "group", id, 5000, observer);
		var bootstrap = new RaftBootstrap(store, "node-a");
		var transport = await(HttpRaftTransport.forBootstrap(bootstrap, serverOptions(id), clientOptions(id)));
		var node = new AtomicReference<VertxLeaderNode>();
		int port = await(transport.listen(envelope -> node.get() == null ? Future.failedFuture("Bootstrap pending")
			: node.get().receive(envelope)));
		var local = new Local(id, vertx, store, bootstrap, transport, new NodeEndpoint("127.0.0.1", port, id), node);
		locals.add(local); return local;
	}

	List<NodeEndpoint> endpoints() { return locals.stream().map(Local::endpoint).toList(); }

	@Test void defaultThreeWaitsThenFormsOneClusterAndStartsFromDurableConfiguration() throws Exception {
		var a = local("node-a"); var b = local("node-b");
		assertThrows(Exception.class, () -> await(a.transport().bootstrap(endpoints())));
		assertEquals(RaftBootstrapState.UNASSIGNED, await(a.store().bootstrapState()));
		assertThrows(Exception.class, () -> await(b.transport().fetchBootstrap(a.endpoint())));
		var c = local("node-c");
		var formed = await(a.transport().bootstrap(endpoints()));
		assertTrue(formed.failures().isEmpty(), formed.failures().toString());
		assertEquals(3, formed.genesis().targetVoterCount());
		assertEquals(Set.of("node-a", "node-b", "node-c"), formed.genesis().voters());
		for (var local : locals) {
			assertEquals(new RaftBootstrapState(formed.genesis(), true), await(local.store().bootstrapState()));
			await(local.transport().discover(endpoints()));
			var node = await(VertxLeaderNode.fromStore(local.store(), new RaftElection.Timing(800, 1600, 120),
				new Random(locals.indexOf(local) + 3), local.transport()));
			node.exceptionHandler(cause -> {});
			local.node().set(node);
		}
		for (var local : locals) await(local.node().get().start());
		Promise<VertxLeaderNode> leader = Promise.promise();
		long timer = a.vertx().setPeriodic(25, id -> {
			for (var local : locals) local.node().get().leadership().onSuccess(value -> {
				if (value.leader()) leader.tryComplete(local.node().get());
			});
		});
		try { assertEquals("bootstrapped", await(await(leader.future()).propose("bootstrapped")).data()); }
		finally { a.vertx().cancelTimer(timer); }
		assertEquals(formed.genesis(), await(b.transport().fetchBootstrap(a.endpoint())));
		// Bootstrap's authority is not a runtime election dependency after installation.
		await(a.node().get().close()); await(a.transport().close());
		Promise<VertxLeaderNode> surviving = Promise.promise();
		long recovery = b.vertx().setPeriodic(25, id -> {
			for (var local : List.of(b, c)) local.node().get().leadership().onSuccess(value -> {
				if (value.leader()) surviving.tryComplete(local.node().get());
			});
		});
		try { assertEquals("without-authority", await(await(surviving.future()).propose("without-authority")).data()); }
		finally { b.vertx().cancelTimer(recovery); }
		// Restart the remaining majority from disk while the authority stays offline.
		for (var old : List.of(b, c)) { await(old.node().get().close()); await(old.transport().close()); }
		var recoveredB = local("node-b"); var recoveredC = local("node-c");
		var recovered = List.of(recoveredB, recoveredC);
		var liveEndpoints = recovered.stream().map(Local::endpoint).toList();
		for (var local : recovered) {
			await(local.transport().discover(liveEndpoints));
			var node = await(VertxLeaderNode.fromStore(local.store(), new RaftElection.Timing(800, 1600, 120),
				new Random(recovered.indexOf(local) + 9), local.transport()));
			node.exceptionHandler(cause -> {}); local.node().set(node); await(node.start());
		}
		Promise<Local> restartedLeader = Promise.promise();
		long restartedTimer = recoveredB.vertx().setPeriodic(25, id -> {
			for (var local : recovered) local.node().get().leadership().onSuccess(value -> {
				if (value.leader()) restartedLeader.tryComplete(local);
			});
		});
		try {
			var elected = await(restartedLeader.future());
			assertEquals("restarted", await(elected.node().get().propose("restarted")).data());
			assertTrue(await(elected.store().persistentState()).log().stream()
				.anyMatch(entry -> entry.data().equals("bootstrapped")));
			assertEquals(formed.genesis(), await(elected.store().bootstrapState()).genesis());
		} finally { recoveredB.vertx().cancelTimer(restartedTimer); }
	}

	@Test void authorityPersistsBeforeSendingAnyInstallAndRetriesSameSet() throws Exception {
		Promise<Void> gate = Promise.promise(); AtomicBoolean armed = new AtomicBoolean();
		CountDownLatch reached = new CountDownLatch(1);
		var a = local("node-a", stage -> {
			if (armed.get() && stage == VertxRaftStore.WriteStage.STAGED) { reached.countDown(); return gate.future(); }
			return Future.succeededFuture();
		});
		var b = local("node-b"); var c = local("node-c");
		armed.set(true);
		var pending = a.transport().bootstrap(endpoints());
		assertTrue(reached.await(5, TimeUnit.SECONDS));
		assertFalse(pending.isComplete());
		assertEquals(RaftBootstrapState.UNASSIGNED, await(b.store().bootstrapState()));
		assertEquals(RaftBootstrapState.UNASSIGNED, await(c.store().bootstrapState()));
		gate.complete();
		var original = await(pending).genesis();
		var again = await(a.transport().bootstrap(List.of()));
		assertEquals(original, again.genesis());
		assertTrue(again.failures().isEmpty()); // Previously verified routes are retained for exact-set retry.
	}

	@Test void interruptedInstallDoesNotChangeSelectionAndFollowerCanFetchOriginal() throws Exception {
		var a = local("node-a"); var b = local("node-b"); var c = local("node-c");
		var peers = await(a.transport().discover(endpoints())).peers();
		var original = await(a.bootstrap().form(peers));
		await(c.transport().close());
		var partial = await(a.transport().bootstrap(List.of(b.endpoint())));
		assertEquals(original, partial.genesis());
		assertTrue(partial.failures().containsKey("node-c"));
		assertEquals(original, await(a.store().bootstrapState()).genesis());
		assertEquals(original, await(b.transport().fetchBootstrap(a.endpoint())));
		await(c.bootstrap().install(original, "node-a"));
		assertThrows(Exception.class, () -> await(c.bootstrap().install(
			RaftGenesis.create("foreign", "group", "node-a", original.voters(), 3), "node-a")));
	}

	@Test void authenticatedNonAuthorityCannotInstallAndLocalNodeCannotStartUnassigned() throws Exception {
		var a = local("node-a"); var b = local("node-b"); var c = local("node-c");
		var genesis = await(a.bootstrap().form(await(a.transport().discover(endpoints())).peers()));
		var unauthorized = c.vertx().createHttpClient(clientOptions("node-c"));
		try {
			var request = await(unauthorized.request(new RequestOptions().setSsl(true).setHost("127.0.0.1")
				.setPort(b.endpoint().port()).setMethod(HttpMethod.POST).setURI("/leader/v1/groups/group/bootstrap/install")));
			assertEquals(403, await(request.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE)
				.send(RaftWireCodec.genesis(genesis))).statusCode());
		} finally { await(unauthorized.close()); }
		var authorityClient = a.vertx().createHttpClient(clientOptions("node-a"));
		try {
			var excluded = RaftGenesis.create(genesis.clusterId(), "group", "node-a",
				Set.of("node-a", "node-c", "node-d"), 3);
			var request = await(authorityClient.request(new RequestOptions().setSsl(true).setHost("127.0.0.1")
				.setPort(b.endpoint().port()).setMethod(HttpMethod.POST).setURI("/leader/v1/groups/group/bootstrap/install")));
			assertEquals(409, await(request.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE)
				.send(RaftWireCodec.genesis(excluded))).statusCode());
		} finally { await(authorityClient.close()); }
		assertEquals(RaftBootstrapState.UNASSIGNED, await(b.store().bootstrapState()));
		assertThrows(Exception.class, () -> await(VertxLeaderNode.fromStore(b.store(), RaftElection.Timing.defaults(),
			new Random(1), b.transport())));
		assertThrows(Exception.class, () -> await(b.bootstrap().form(List.of())));
	}
	@Test void installationResponseWaitsForVoterDurability() throws Exception {
		var a = local("node-a");
		Promise<Void> gate = Promise.promise(); AtomicBoolean armed = new AtomicBoolean();
		CountDownLatch reached = new CountDownLatch(1);
		var b = local("node-b", stage -> {
			if (armed.get() && stage == VertxRaftStore.WriteStage.STAGED) { reached.countDown(); return gate.future(); }
			return Future.succeededFuture();
		});
		local("node-c"); armed.set(true);
		var formation = a.transport().bootstrap(endpoints());
		assertTrue(reached.await(5, TimeUnit.SECONDS));
		assertFalse(formation.isComplete());
		gate.complete();
		var result = await(formation);
		assertTrue(result.failures().isEmpty());
		assertEquals(new RaftBootstrapState(result.genesis(), true), await(b.store().bootstrapState()));
	}

	@Test void authorityRestartReusesRecordAndIgnoresNewLocalTarget() throws Exception {
		var a = local("node-a"); local("node-b"); local("node-c");
		var original = await(a.bootstrap().form(await(a.transport().discover(endpoints())).peers()));
		await(a.transport().close()); await(a.store().close());
		var recovered = local("node-a");
		assertEquals(original, await(new RaftBootstrap(recovered.store(), "node-a", 5).form(List.of())));
		var result = await(recovered.transport().bootstrap(endpoints()));
		assertEquals(original, result.genesis()); assertTrue(result.failures().isEmpty());
	}

	@Test void manualConstructorCannotBypassConfiguredBootstrapAuthority() throws Exception {
		var b = local("node-b");
		var bypass = new VertxLeaderNode(b.store(), "manual", Set.of("node-b"), RaftElection.Timing.defaults(),
			new Random(1), b.transport());
		bypass.exceptionHandler(cause -> {});
		assertThrows(Exception.class, () -> await(bypass.start()));
		await(bypass.close());
		try (var recovered = FileRaftStore.open(directory.resolve("node-b"), "group", "node-b")) {
			assertEquals(RaftBootstrapState.UNASSIGNED, recovered.bootstrapState());
			assertEquals(0, recovered.hardState().currentTerm());
		}
	}

}
