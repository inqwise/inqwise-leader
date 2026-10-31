package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;
import static com.inqwise.leader.HttpRaftTransportTest.clientOptions;
import static com.inqwise.leader.HttpRaftTransportTest.serverOptions;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

class HttpRaftServerTest {
	record Reply(int status, String body) {}
	final Vertx vertx = Vertx.vertx();
	final Context context = vertx.getOrCreateContext();
	final HttpClient client = vertx.createHttpClient(clientOptions("node-a"));
	final List<HttpRaftServer> servers = new ArrayList<>();

	@AfterEach void shutdown() throws Exception {
		for (var server : servers) await(ContextDispatch.call(context, server::close));
		await(client.close()); await(vertx.close());
	}

	HttpRaftServer server(Consumer<HttpRaftServer> routes, long timeout) throws Exception {
		var server = await(ContextDispatch.call(context, () -> {
			var result = new HttpRaftServer(context, serverOptions("node-b"), "/group", "node-b",
				connection -> RaftTlsIdentity.nodeId((X509Certificate) connection.peerCertificates().getFirst()), 1, timeout);
			routes.accept(result); return result.listen().map(result);
		}));
		servers.add(server); return server;
	}

	RequestOptions request(HttpRaftServer server, HttpMethod method, String suffix) {
		return new RequestOptions().setSsl(true).setHost("127.0.0.1").setPort(server.actualPort())
			.setMethod(method).setURI("/group" + suffix).setTimeout(5000);
	}
	Future<Reply> exchange(HttpRaftServer server, HttpMethod method, String suffix, Buffer body) {
		return client.request(request(server, method, suffix)).compose(request -> request
			.putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE).send(body)).compose(response ->
			response.body().map(bytes -> new Reply(response.statusCode(), bytes.toString())));
	}
	void live(HttpRaftServer server) {
		server.<String>get("/live", Buffer::buffer, (peer, ignored, completion) -> completion.succeed("ready"), peer -> 0, 400);
	}

	@Test void typedHandlersDecodeOnceRunOnOwnerAndIgnoreDuplicateCompletion() throws Exception {
		var decodes = new AtomicInteger(); var actual = new AtomicReference<Context>();
		var authenticated = new AtomicReference<String>();
		var server = server(listener -> listener.<String, String>post("/echo", body -> {
			decodes.incrementAndGet(); return body.toString();
		}, Buffer::buffer, (peer, message, completion) -> {
			actual.set(Vertx.currentContext()); authenticated.set(peer);
			completion.succeed(message); completion.succeed("duplicate");
		}, peer -> 0, 400), 5000);
		assertEquals(new Reply(200, "first"), await(exchange(server, HttpMethod.POST, "/echo", Buffer.buffer("first"))));
		assertEquals(new Reply(200, "second"), await(exchange(server, HttpMethod.POST, "/echo", Buffer.buffer("second"))));
		assertEquals(2, decodes.get()); assertSame(context, actual.get()); assertEquals("node-a", authenticated.get());
	}

	@Test void expiredPolicyRetainsCapacityUntilLateCompletionAndNeverWritesASecondResponse() throws Exception {
		var held = Promise.<RaftRequestCompletion<String>>promise();
		var server = server(listener -> {
			listener.<String>get("/held", Buffer::buffer, (peer, ignored, completion) -> held.complete(completion), peer -> 0, 409);
			live(listener);
		}, 200);
		var waiting = exchange(server, HttpMethod.GET, "/held", Buffer.buffer());
		var completion = await(held.future());
		assertEquals(409, await(waiting).status());
		assertFalse(await(ContextDispatch.call(context, () -> Future.succeededFuture(completion.isActive()))));
		assertEquals(429, await(exchange(server, HttpMethod.GET, "/live", Buffer.buffer())).status());
		completion.succeed("late");
		await(ContextDispatch.call(context, () -> Future.succeededFuture()));
		assertEquals(new Reply(200, "ready"), await(exchange(server, HttpMethod.GET, "/live", Buffer.buffer())));
	}

	@Test void closeDrainsAcceptedDecoderAndExpiredRequestCannotReachPolicy() throws Exception {
		var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var dispatched = new AtomicInteger();
		var server = server(listener -> listener.<String, String>post("/large", body -> {
			entered.countDown();
			if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Gate timed out");
			return body.toString();
		}, Buffer::buffer, (peer, message, completion) -> {
			dispatched.incrementAndGet(); completion.succeed(message);
		}, peer -> 0, 400), 200);
		try {
			var waiting = exchange(server, HttpMethod.POST, "/large", Buffer.buffer(new byte[16385]));
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			assertEquals(400, await(waiting).status());
			var closing = ContextDispatch.call(context, server::close);
			await(ContextDispatch.call(context, () -> Future.succeededFuture()));
			assertFalse(closing.isComplete()); release.countDown(); await(closing);
			assertEquals(0, dispatched.get());
		} finally { release.countDown(); }
	}

	@Test void partialBodyDeadlineReleasesCapacityWithoutDecodingOrCallingPolicy() throws Exception {
		var decodes = new AtomicInteger(); var dispatched = new AtomicInteger();
		var server = server(listener -> {
			listener.<String, String>post("/partial", body -> {
				decodes.incrementAndGet(); return body.toString();
			}, Buffer::buffer, (peer, message, completion) -> {
				dispatched.incrementAndGet(); completion.succeed(message);
			}, peer -> 0, 400);
			live(listener);
		}, 200);
		var partial = await(client.request(request(server, HttpMethod.POST, "/partial")));
		partial.setChunked(true).putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE);
		await(partial.write(Buffer.buffer("unfinished")));
		assertEquals(400, await(partial.response()).statusCode());
		partial.reset();
		assertEquals(new Reply(200, "ready"), await(exchange(server, HttpMethod.GET, "/live", Buffer.buffer())));
		assertEquals(0, decodes.get()); assertEquals(0, dispatched.get());
	}
}
