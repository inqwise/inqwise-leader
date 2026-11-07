package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;

class TcpRaftTransportTest {
	@TempDir Path directory;
	final List<Vertx> runtimes = new ArrayList<>();
	final List<TcpRaftTransport> transports = new ArrayList<>();
	final List<VertxLeaderNode> nodes = new ArrayList<>();

	@AfterEach void shutdown() throws Exception {
		for (var node : nodes) await(node.close());
		for (var transport : transports) await(transport.close());
		for (var runtime : runtimes) await(runtime.close());
	}

	static String fixture(String name) {
		return Path.of("src/test/resources/tls", name).toAbsolutePath().toString();
	}
	static PemKeyCertOptions keys(String id) {
		return new PemKeyCertOptions().setKeyPath(fixture(id + ".key")).setCertPath(fixture(id + ".crt"));
	}
	static PemTrustOptions trust() { return new PemTrustOptions().addCertPath(fixture("ca.crt")); }
	static NetServerOptions serverOptions(String id) {
		return new NetServerOptions().setHost("127.0.0.1").setPort(0).setSsl(true).setClientAuth(ClientAuth.REQUIRED)
			.setKeyCertOptions(keys(id)).setTrustOptions(trust());
	}
	static NetClientOptions clientOptions(String id) {
		return new NetClientOptions().setSsl(true).setHostnameVerificationAlgorithm("HTTPS").setKeyCertOptions(keys(id)).setTrustOptions(trust());
	}

	Vertx runtime() { var vertx = Vertx.vertx(); runtimes.add(vertx); return vertx; }
	TcpRaftTransport transport(Vertx vertx, String id, String group, String cluster) {
		return transport(vertx.getOrCreateContext(), id, group, cluster);
	}
	TcpRaftTransport transport(Context context, String id, String group, String cluster) {
		var transport = new TcpRaftTransport(context, new RaftIdentity(id, group), cluster,
			serverOptions(id), clientOptions(id));
		transports.add(transport); return transport;
	}
	NodeEndpoint endpoint(int port, String id) { return new NodeEndpoint("127.0.0.1", port, id); }
	RaftElection.Envelope message(String group, String cluster, String from, String to) {
		return new RaftElection.Envelope(group, cluster, from, to,
			new RaftElection.VoteRequest(1, new RaftElection.LogPosition(0, 0)));
	}

	@Test void identityHandshakeAndDispatchUsePersistedIdentityAndOwnerContext() throws Exception {
		var first = transport(runtime(), "node-a", "group / 雨", "cluster");
		var secondRuntime = runtime();
		var owner = secondRuntime.getOrCreateContext();
		var second = transport(owner, "node-b", "group / 雨", "cluster");
		CountDownLatch received = new CountDownLatch(1);
		AtomicReference<Context> actual = new AtomicReference<>();
		await(first.listen(envelope -> Future.succeededFuture()));
		int port = await(second.listen(envelope -> {
			actual.set(Vertx.currentContext()); received.countDown(); return Future.succeededFuture();
		}));
		var discovery = await(first.discover(List.of(endpoint(port, null))));
		assertTrue(discovery.failures().isEmpty(), discovery.failures().toString());
		assertEquals("node-b", discovery.peers().getFirst().identity().nodeId());
		await(first.send(message("group / 雨", "cluster", "node-a", "node-b")));
		assertTrue(received.await(5, TimeUnit.SECONDS));
		assertSame(owner, actual.get());
		// Failed refresh retains the verified route; discovery never reduces quorum.
		assertFalse(await(first.discover(List.of(endpoint(port, "node-c")))).failures().isEmpty());
		await(first.send(message("group / 雨", "cluster", "node-a", "node-b")));
	}

	@Test void rejectForeignClusterAndExpectedIdentityDuringHandshake() throws Exception {
		var first = transport(runtime(), "node-a", "group", "cluster");
		var second = transport(runtime(), "node-b", "group", "foreign");
		await(first.listen(envelope -> Future.succeededFuture()));
		int port = await(second.listen(envelope -> Future.succeededFuture()));
		var result = await(first.discover(List.of(endpoint(port, "node-b"))));
		assertTrue(result.peers().isEmpty());
		assertEquals(1, result.failures().size());
		assertThrows(Exception.class, () -> await(first.send(message("group", "cluster", "node-a", "node-b"))));
	}

	@Test void duplicateIdentityEndpointsAreQuarantined() throws Exception {
		var first = transport(runtime(), "node-a", "group", "cluster");
		var second = transport(runtime(), "node-b", "group", "cluster");
		var clone = transport(runtime(), "node-b", "group", "cluster");
		await(first.listen(envelope -> Future.succeededFuture()));
		int secondPort = await(second.listen(envelope -> Future.succeededFuture()));
		int clonePort = await(clone.listen(envelope -> Future.succeededFuture()));
		assertEquals(1, await(first.discover(List.of(endpoint(secondPort, null)))).peers().size());
		var result = await(first.discover(List.of(endpoint(secondPort, null), endpoint(clonePort, null))));
		assertTrue(result.peers().isEmpty());
		assertEquals(2, result.failures().size());
		assertThrows(Exception.class, () -> await(first.send(message("group", "cluster", "node-a", "node-b"))));
	}

	@Test void closeCancelsInFlightDispatchAndRejectsLateWork() throws Exception {
		var first = transport(runtime(), "node-a", "group", "cluster");
		var second = transport(runtime(), "node-b", "group", "cluster");
		await(first.listen(envelope -> Future.succeededFuture()));
		Promise<Void> gate = Promise.promise();
		CountDownLatch received = new CountDownLatch(1);
		int port = await(second.listen(envelope -> { received.countDown(); return gate.future(); }));
		await(first.discover(List.of(endpoint(port, "node-b"))));
		var pending = first.send(message("group", "cluster", "node-a", "node-b"));
		assertTrue(received.await(5, TimeUnit.SECONDS));
		await(first.close());
		assertThrows(Exception.class, () -> await(pending));
		gate.complete();
		await(first.close());
		assertThrows(Exception.class, () -> await(first.discover(List.of(endpoint(port, "node-b")))));
	}

	@Test void localCertificateMustMatchPersistedIdentity() throws Exception {
		var first = transport(runtime(), "node-a", "group", "cluster");
		await(first.listen(envelope -> Future.succeededFuture()));
		var runtime = runtime();
		var inconsistent = new TcpRaftTransport(runtime.getOrCreateContext(), new RaftIdentity("node-b", "group"),
			"cluster", serverOptions("node-c"), clientOptions("node-c"));
		transports.add(inconsistent);
		AtomicInteger calls = new AtomicInteger();
		int port = await(inconsistent.listen(envelope -> { calls.incrementAndGet(); return Future.succeededFuture(); }));
		var result = await(first.discover(List.of(endpoint(port, "node-b"))));
		assertTrue(result.peers().isEmpty());
		assertEquals(1, result.failures().size());
		assertEquals(0, calls.get());
	}

	@Test void closingDuringBindPreventsLateListenerActivation() throws Exception {
		var vertx = runtime();
		var transport = transport(vertx, "node-a", "group", "cluster");
		var listening = transport.listen(envelope -> Future.succeededFuture());
		await(transport.close());
		assertThrows(Exception.class, () -> await(listening));
		assertThrows(Exception.class, () -> await(transport.listen(envelope -> Future.succeededFuture())));
	}

	@Test void boundedSendQueueRejectsOverloadAndClosesPendingWork() throws Exception {
		var first = transport(runtime(), "node-a", "group", "cluster");
		var second = transport(runtime(), "node-b", "group", "cluster");
		await(first.listen(envelope -> Future.succeededFuture()));
		Promise<Void> gate = Promise.promise();
		int port = await(second.listen(envelope -> gate.future()));
		await(first.discover(List.of(endpoint(port, "node-b"))));
		var sending = new ArrayList<Future<Void>>();
		for (int i = 0; i < 65; i++) sending.add(first.send(message("group", "cluster", "node-a", "node-b")));
		assertThrows(Exception.class, () -> await(sending.getLast()));
		assertTrue(sending.getLast().cause().getMessage().contains("limit"));
		await(first.close());
		gate.complete();
		await(Future.join(sending).recover(cause -> Future.succeededFuture(null)));
	}

	@Test void dispatchDeadlineFailsWithoutTreatingTcpAsRaftAcknowledgement() throws Exception {
		var first = transport(runtime(), "node-a", "group", "cluster");
		var second = transport(runtime(), "node-b", "group", "cluster");
		await(first.listen(envelope -> Future.succeededFuture()));
		Promise<Void> gate = Promise.promise();
		int port = await(second.listen(envelope -> gate.future()));
		await(first.discover(List.of(endpoint(port, "node-b"))));
		try {
			assertThrows(Exception.class, () -> await(first.send(message("group", "cluster", "node-a", "node-b"))));
		} finally { gate.tryComplete(); }
	}

	@Test void independentVertxNodesElectAndReplicateOverRealTcp() throws Exception {
		var voters = Set.of("node-a", "node-b", "node-c");
		var endpoints = new ArrayList<NodeEndpoint>();
		for (String id : voters) {
			var vertx = runtime();
			var store = VertxRaftStore.create(vertx, directory.resolve(id), "group", id);
			await(store.open());
			var transport = transport(store.context(), id, "group", "cluster");
			var node = new VertxLeaderNode(store, "cluster", voters, new RaftElection.Timing(1000, 2200, 150),
				new Random(nodes.size() + 1), transport);
			nodes.add(node);
			node.exceptionHandler(cause -> {}); // Contact failures are expected during the leader-loss phase.
			int port = await(transport.listen(node::receive));
			endpoints.add(endpoint(port, id));
		}
		for (var transport : transports) {
			assertTrue(await(transport.discover(endpoints)).failures().isEmpty());
		}
		for (var node : nodes) await(node.start());
		Promise<VertxLeaderNode> elected = Promise.promise();
		long timer = runtimes.getFirst().setPeriodic(25, id -> {
			for (var node : nodes) node.leadership().onSuccess(value -> { if (value.leader()) elected.tryComplete(node); });
		});
		VertxLeaderNode leader;
		try { leader = await(elected.future()); } finally { runtimes.getFirst().cancelTimer(timer); }
		var committed = await(leader.propose("over-tcp"));
		assertEquals("over-tcp", committed.data());
		int leaderIndex = nodes.indexOf(leader);
		await(leader.close());
		await(transports.get(leaderIndex).close());
		Promise<VertxLeaderNode> successor = Promise.promise();
		long recoveryTimer = runtimes.getFirst().setPeriodic(25, id -> {
			for (var candidate : nodes) if (candidate != leader) candidate.leadership().onSuccess(value -> {
				if (value.leader()) successor.tryComplete(candidate);
			});
		});
		VertxLeaderNode next;
		try { next = await(successor.future()); } finally { runtimes.getFirst().cancelTimer(recoveryTimer); }
		assertTrue(await(next.snapshot()).hardState().currentTerm() > committed.term());
		assertEquals("after-failure", await(next.propose("after-failure")).data());
	}

	@Test void tlsHandshakeRejectsSpoofedIdentityAndProtocolClaims() throws Exception {
		var vertx = runtime(); var target = transport(vertx, "node-b", "group", "cluster");
		var calls = new AtomicInteger();
		int port = await(target.listen(envelope -> { calls.incrementAndGet(); return Future.succeededFuture(); }));
		var client = vertx.createNetClient(clientOptions("node-a"));
		try {
			var spoofed = await(client.connect(port, "127.0.0.1").map(TcpRaftRpcTest.Raw::new));
			spoofed.next(); spoofed.hello("node-c");
			assertTrue(spoofed.closed.await(5, TimeUnit.SECONDS));
			var raw = await(client.connect(port, "127.0.0.1").map(TcpRaftRpcTest.Raw::new));
			raw.next(); raw.hello("node-a"); long id = 0;
			for (var envelope : List.of(message("group", "cluster", "node-c", "node-b"),
				message("group", "foreign", "node-a", "node-b"), message("group", "cluster", "node-a", "node-c"))) {
				await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST,
					TcpRaftProtocol.operation("/request-vote"), ++id, 0, RaftWireCodec.encode(envelope))));
				assertEquals(400, raw.next().status());
			}
			await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST,
				TcpRaftProtocol.operation("/append-entries"), ++id, 0,
				RaftWireCodec.encode(message("group", "cluster", "node-a", "node-b")))));
			assertEquals(400, raw.next().status()); assertEquals(0, calls.get());
		} finally { await(client.close()); }
	}

	@Test void insecureOrMixedTlsConfigurationIsRejected() throws Exception {
		var context = runtime().getOrCreateContext(); var identity = new RaftIdentity("node-a", "group");
		assertThrows(IllegalArgumentException.class, () -> new TcpRaftTransport(context, identity, "cluster",
			serverOptions("node-a"), new NetClientOptions()));
		for (var options : List.of(clientOptions("node-a").setHostnameVerificationAlgorithm(""),
			clientOptions("node-a").setTrustAll(true), clientOptions("node-a").setKeyCertOptions(null))) {
			assertThrows(IllegalArgumentException.class, () -> new TcpRaftTransport(context, identity, "cluster",
				serverOptions("node-a"), options));
		}
	}

	@Test void largeSnapshotUsesBulkConnectionAndDoesNotDelayControlDispatch() throws Exception {
		var first = transport(runtime(), "node-a", "group", "cluster");
		var second = transport(runtime(), "node-b", "group", "cluster");
		var checkpoint = new RaftSnapshot(100, 2, RaftConfiguration.stable(Set.of("node-a", "node-b"), Set.of()),
			0, "snapshot-data".repeat(5000));
		var snapshot = new RaftElection.Envelope("group", "cluster", "node-a", "node-b",
			new RaftElection.InstallSnapshot(2, 10, checkpoint));
		var received = Promise.<Void>promise(); var gate = Promise.<Void>promise();
		await(first.listen(envelope -> Future.succeededFuture()));
		int port = await(second.listen(envelope -> {
			assertSame(second.context(), Vertx.currentContext());
			if (envelope.message() instanceof RaftElection.InstallSnapshot install) {
				assertEquals(checkpoint, install.snapshot()); received.tryComplete(); return gate.future();
			}
			return Future.succeededFuture();
		}));
		await(first.discover(List.of(endpoint(port, "node-b"))));
		var pending = first.send(snapshot);
		try {
			await(received.future());
			await(first.send(message("group", "cluster", "node-a", "node-b")));
			assertFalse(pending.isComplete());
			gate.complete(); await(pending);
		} finally { gate.tryComplete(); }
	}
}
