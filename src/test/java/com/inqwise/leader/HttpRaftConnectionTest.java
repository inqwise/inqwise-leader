package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;
import static com.inqwise.leader.HttpRaftTransportTest.clientOptions;
import static com.inqwise.leader.HttpRaftTransportTest.serverOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;

class HttpRaftConnectionTest {
	record Held(HttpServerRequest request, Context context) {}
	final Vertx vertx = Vertx.vertx();
	final Context context = vertx.getOrCreateContext();
	final List<HttpRaftClient> clients = new ArrayList<>();

	@AfterEach void shutdown() throws Exception {
		for (var client : clients) await(ContextDispatch.call(context, client::close));
		await(vertx.close());
	}

	HttpRaftClient client(long timeout) throws Exception {
		var client = await(ContextDispatch.call(context, () -> Future.succeededFuture(new HttpRaftClient(context,
			clientOptions("node-a"), "/group", "node-a", connection -> RaftTlsIdentity.nodeId((java.security.cert.X509Certificate) connection.peerCertificates().getFirst()),
			1, timeout))));
		clients.add(client); return client;
	}

	void identity(HttpServerRequest request, String id) {
		try {
			request.response().putHeader(HttpRaftClient.NODE_HEADER, "node-b")
				.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE)
				.end(RaftWireCodec.identity(new RaftIdentity(id, "group"), "cluster"));
		} catch (Exception e) { request.response().setStatusCode(500).end(); }
	}

	@Test void typedIdentityVerificationFailureReleasesCapacityAndCallbacksUseOwnerContext() throws Exception {
		var calls = new AtomicInteger();
		var server = await(vertx.createHttpServer(serverOptions("node-b")).requestHandler(request ->
			identity(request, calls.getAndIncrement() == 0 ? "node-c" : "node-b")).listen());
		var client = client(5000);
		RaftConnection connection = client.connection(new NodeEndpoint("127.0.0.1", server.actualPort(), "node-b"));
		var first = Promise.<RaftWireCodec.PeerIdentity>promise();
		context.runOnContext(ignored -> connection.identify((value, failure) -> {
			if (Vertx.currentContext() != context) first.tryFail("Wrong callback context");
			else first.complete(value, failure);
		}));
		assertThrows(Exception.class, () -> await(first.future()));
		assertTrue(first.future().cause().getMessage().contains("identity"));
		var second = Promise.<RaftWireCodec.PeerIdentity>promise();
		context.runOnContext(ignored -> connection.identify(second));
		assertEquals("node-b", await(second.future()).nodeId());
		assertEquals(2, calls.get());
	}

	@Test void timeoutCompletesOnceAndReleasesSlotBeforeLateResponse() throws Exception {
		var held = Promise.<Held>promise();
		var count = new AtomicInteger();
		var server = await(vertx.createHttpServer(serverOptions("node-b")).requestHandler(request -> {
			if (count.getAndIncrement() == 0) held.complete(new Held(request, Vertx.currentContext())); else identity(request, "node-b");
		}).listen());
		var client = client(1500);
		RaftConnection connection = client.connection(new NodeEndpoint("127.0.0.1", server.actualPort(), "node-b"));
		var completed = Promise.<Void>promise(); var completions = new AtomicInteger();
		context.runOnContext(ignored -> connection.identify((value, failure) -> {
			completions.incrementAndGet();
			if (failure instanceof java.util.concurrent.TimeoutException) completed.tryComplete();
			else completed.tryFail("Expected deadline timeout, got " + failure);
		}));
		var request = await(held.future()); await(completed.future());
		var next = Promise.<RaftWireCodec.PeerIdentity>promise();
		context.runOnContext(ignored -> connection.identify(next));
		assertEquals("node-b", await(next.future()).nodeId());
		request.context().runOnContext(ignored -> identity(request.request(), "node-b"));
		await(ContextDispatch.call(context, client::close));
		assertEquals(1, completions.get());
	}

	@Test void oversizedResponseIsRejectedAndDoesNotConsumeTheNextRequestSlot() throws Exception {
		var count = new AtomicInteger();
		var server = await(vertx.createHttpServer(serverOptions("node-b")).requestHandler(request -> {
			if (count.getAndIncrement() == 0) {
				request.response().putHeader(HttpRaftClient.NODE_HEADER, "node-b")
					.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE)
					.end(io.vertx.core.buffer.Buffer.buffer(new byte[RaftWireCodec.MAX_BYTES + 1]));
			} else identity(request, "node-b");
		}).listen());
		var client = client(5000);
		RaftConnection connection = client.connection(new NodeEndpoint("127.0.0.1", server.actualPort(), "node-b"));
		var first = Promise.<RaftWireCodec.PeerIdentity>promise();
		context.runOnContext(ignored -> connection.identify(first));
		assertThrows(Exception.class, () -> await(first.future()));
		assertTrue(first.future().cause().getMessage().contains("too large"));
		var second = Promise.<RaftWireCodec.PeerIdentity>promise();
		context.runOnContext(ignored -> connection.identify(second));
		assertEquals("node-b", await(second.future()).nodeId());
	}

	@Test void expiredCodecWorkRetainsCapacityAndCloseDrainsAcceptedWorkerBeforeCompleting() throws Exception {
		var client = client(150);
		var entered = new java.util.concurrent.CountDownLatch(1);
		var release = new java.util.concurrent.CountDownLatch(1);
		var first = Promise.<Void>promise();
		var endpoint = new NodeEndpoint("127.0.0.1", 9, "node-b");
		try {
			context.runOnContext(ignored -> client.request(endpoint, io.vertx.core.http.HttpMethod.POST,
				"/request-vote", () -> {
					entered.countDown();
					if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Gate timed out");
					return io.vertx.core.buffer.Buffer.buffer();
				}, (bytes, peer) -> null, true, first));
			assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
			assertThrows(Exception.class, () -> await(first.future()));
			assertInstanceOf(java.util.concurrent.TimeoutException.class, first.future().cause());
			var rejected = Promise.<RaftWireCodec.PeerIdentity>promise();
			RaftConnection connection = client.connection(endpoint);
			context.runOnContext(ignored -> connection.identify(rejected));
			assertThrows(Exception.class, () -> await(rejected.future()));
			assertTrue(rejected.future().cause().getMessage().contains("limit"));
			var closing = ContextDispatch.call(context, client::close);
			await(ContextDispatch.call(context, () -> Future.succeededFuture()));
			assertFalse(closing.isComplete());
			release.countDown();
			await(closing);
		} finally { release.countDown(); }
	}

	@Test void closeCompletesPendingCallbackOnceAndRejectsNewWork() throws Exception {
		var received = Promise.<Void>promise();
		var server = await(vertx.createHttpServer(serverOptions("node-b"))
			.requestHandler(request -> received.tryComplete()).listen());
		var client = client(5000);
		RaftConnection connection = client.connection(new NodeEndpoint("127.0.0.1", server.actualPort(), "node-b"));
		var completions = new AtomicInteger(); var first = Promise.<Void>promise();
		await(ContextDispatch.call(context, () -> {
			connection.identify((value, failure) -> {
				completions.incrementAndGet();
				if (failure == null) first.tryFail("Expected cancellation"); else first.tryComplete();
			});
			return Future.succeededFuture();
		}));
		await(received.future());
		await(ContextDispatch.call(context, client::close));
		await(first.future());
		var after = Promise.<RaftWireCodec.PeerIdentity>promise();
		context.runOnContext(ignored -> connection.identify(after));
		assertThrows(Exception.class, () -> await(after.future()));
		assertEquals(1, completions.get());
	}
}
