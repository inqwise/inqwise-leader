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
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;

class HttpRaftTransportTest {
	@TempDir Path directory;
	final List<Vertx> runtimes = new ArrayList<>();
	final List<HttpRaftTransport> transports = new ArrayList<>();
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
	static HttpServerOptions serverOptions(String id) {
		return new HttpServerOptions().setHost("127.0.0.1").setPort(0).setSsl(true).setClientAuth(ClientAuth.REQUIRED)
			.setKeyCertOptions(keys(id)).setTrustOptions(trust());
	}
	static HttpClientOptions clientOptions(String id) {
		return new HttpClientOptions().setSsl(true).setVerifyHost(true).setKeyCertOptions(keys(id)).setTrustOptions(trust());
	}

	Vertx runtime() { var vertx = Vertx.vertx(); runtimes.add(vertx); return vertx; }
	HttpRaftTransport transport(Vertx vertx, String id, String group, String cluster) {
		return transport(vertx.getOrCreateContext(), id, group, cluster);
	}
	HttpRaftTransport transport(Context context, String id, String group, String cluster) {
		var transport = new HttpRaftTransport(context, new RaftIdentity(id, group), cluster,
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

	@Test void rejectSpoofedSenderReceiverClusterAndWrongPathBeforeDispatch() throws Exception {
		var vertx = runtime();
		var target = transport(vertx, "node-b", "group", "cluster");
		AtomicInteger calls = new AtomicInteger();
		int port = await(target.listen(envelope -> { calls.incrementAndGet(); return Future.succeededFuture(); }));
		HttpClient client = vertx.createHttpClient(clientOptions("node-a"));
		try {
			for (var envelope : List.of(message("group", "cluster", "node-c", "node-b"),
				message("group", "foreign", "node-a", "node-b"), message("group", "cluster", "node-a", "node-c"))) {
				assertEquals(400, await(client.request(new RequestOptions().setSsl(true).setHost("127.0.0.1").setPort(port)
					.setMethod(HttpMethod.POST).setURI("/leader/v1/groups/group/request-vote")))
					.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE).send(RaftWireCodec.encode(envelope))
					.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).statusCode());
			}
			var request = await(client.request(new RequestOptions().setSsl(true).setHost("127.0.0.1").setPort(port)
				.setMethod(HttpMethod.POST).setURI("/leader/v1/groups/group/append-entries")));
			assertEquals(400, await(request.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE)
				.send(RaftWireCodec.encode(message("group", "cluster", "node-a", "node-b")))).statusCode());
			assertEquals(0, calls.get());
		} finally { await(client.close()); }
	}

	@Test void rejectChunkedOversizeAndMalformedBodyWithoutDispatch() throws Exception {
		var vertx = runtime();
		var target = transport(vertx, "node-b", "group", "cluster");
		AtomicInteger calls = new AtomicInteger();
		int port = await(target.listen(envelope -> { calls.incrementAndGet(); return Future.succeededFuture(); }));
		var client = vertx.createHttpClient(clientOptions("node-a"));
		try {
			var request = await(client.request(new RequestOptions().setSsl(true).setHost("127.0.0.1").setPort(port)
				.setMethod(HttpMethod.POST).setURI("/leader/v1/groups/group/request-vote")));
			request.setChunked(true).putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE);
			assertEquals(413, await(request.send(Buffer.buffer(new byte[RaftWireCodec.MAX_BYTES + 1]))).statusCode());
			var malformed = await(client.request(new RequestOptions().setSsl(true).setHost("127.0.0.1").setPort(port)
				.setMethod(HttpMethod.POST).setURI("/leader/v1/groups/group/request-vote")));
			assertEquals(400, await(malformed.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE)
				.send(Buffer.buffer("invalid"))).statusCode());
			assertEquals(0, calls.get());
		} finally { await(client.close()); }
	}

	@Test void plaintextDiscoveryRoutesMessagesAndRejectsMissingOrInconsistentIdentityClaims() throws Exception {
		var vertx = runtime(); var context = vertx.getOrCreateContext();
		var first = new HttpRaftTransport(context, new RaftIdentity("node-a", "group"), "cluster",
			new HttpServerOptions().setHost("127.0.0.1").setPort(0), new HttpClientOptions());
		var second = new HttpRaftTransport(context, new RaftIdentity("node-b", "group"), "cluster",
			new HttpServerOptions().setHost("127.0.0.1").setPort(0), new HttpClientOptions());
		transports.add(first); transports.add(second);
		var calls = new AtomicInteger();
		await(first.listen(envelope -> Future.succeededFuture()));
		int port = await(second.listen(envelope -> { calls.incrementAndGet(); return Future.succeededFuture(); }));
		assertTrue(await(first.discover(List.of(endpoint(port, "node-b")))).failures().isEmpty());
		await(first.send(message("group", "cluster", "node-a", "node-b")));
		assertEquals(1, calls.get());
		assertFalse(await(first.discover(List.of(endpoint(port, "node-c")))).failures().isEmpty());
		var client = vertx.createHttpClient();
		try {
			for (String claim : List.of("", "invalid/id")) {
				var request = await(client.request(new RequestOptions().setHost("127.0.0.1").setPort(port)
					.setURI("/leader/v1/groups/group/identity")));
				if (!claim.isEmpty()) request.putHeader(HttpRaftClient.NODE_HEADER, claim);
				assertEquals(403, await(request.send()).statusCode());
			}
			var request = await(client.request(new RequestOptions().setHost("127.0.0.1").setPort(port)
				.setMethod(HttpMethod.POST).setURI("/leader/v1/groups/group/request-vote")));
			assertEquals(400, await(request.putHeader(HttpRaftClient.NODE_HEADER, "node-a")
				.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE)
				.send(RaftWireCodec.encode(message("group", "cluster", "node-c", "node-b")))).statusCode());
			assertEquals(1, calls.get());
		} finally { await(client.close()); }
	}

	@Test void tlsCertificateCannotBeOverriddenByIdentityHeader() throws Exception {
		var vertx = runtime(); var target = transport(vertx, "node-b", "group", "cluster");
		int port = await(target.listen(envelope -> Future.succeededFuture()));
		var client = vertx.createHttpClient(clientOptions("node-a"));
		try {
			var request = await(client.request(new RequestOptions().setHost("127.0.0.1").setPort(port)
				.setURI("/leader/v1/groups/group/identity")));
			assertEquals(403, await(request.putHeader(HttpRaftClient.NODE_HEADER, "node-c").send()).statusCode());
		} finally { await(client.close()); }
	}

	@Test void rejectMixedModesAndRequireVerifiedMutualTlsWhenEnabled() throws Exception {
		var vertx = runtime();
		assertThrows(IllegalArgumentException.class, () -> new HttpRaftTransport(vertx.getOrCreateContext(),
			new RaftIdentity("node-a", "group"), "cluster", new HttpServerOptions(), clientOptions("node-a")));
		assertThrows(IllegalArgumentException.class, () -> new HttpRaftTransport(vertx.getOrCreateContext(),
			new RaftIdentity("node-a", "group"), "cluster", serverOptions("node-a"), new HttpClientOptions()));
		assertThrows(IllegalArgumentException.class, () -> new HttpRaftTransport(vertx.getOrCreateContext(),
			new RaftIdentity("node-a", "group"), "cluster", serverOptions("node-a"), clientOptions("node-a").setVerifyHost(false)));
		assertThrows(IllegalArgumentException.class, () -> new HttpRaftTransport(vertx.getOrCreateContext(),
			new RaftIdentity("node-a", "group"), "cluster", serverOptions("node-a"), clientOptions("node-a").setKeyCertOptions(null)));
		assertThrows(IllegalArgumentException.class, () -> new HttpRaftTransport(vertx.getOrCreateContext(),
			new RaftIdentity("node-a", "group"), "cluster", serverOptions("node-a"), clientOptions("node-a").setTrustAll(true)));
		var target = transport(vertx, "node-b", "group", "cluster");
		int port = await(target.listen(envelope -> Future.succeededFuture()));
		var unauthenticated = vertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustOptions(trust()));
		try {
			assertThrows(Exception.class, () -> await(unauthenticated.request(new RequestOptions().setSsl(true)
				.setHost("127.0.0.1").setPort(port).setURI("/leader/v1/groups/group/identity")).compose(request -> request.send())));
		} finally { await(unauthenticated.close()); }
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
		var inconsistent = new HttpRaftTransport(runtime.getOrCreateContext(), new RaftIdentity("node-b", "group"),
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

	@Test void dispatchDeadlineFailsWithoutTreatingHttpAsRaftAcknowledgement() throws Exception {
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

	@Test void independentVertxNodesElectAndReplicateOverRealHttp() throws Exception {
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
		var committed = await(leader.propose("over-http"));
		assertEquals("over-http", committed.data());
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
}
