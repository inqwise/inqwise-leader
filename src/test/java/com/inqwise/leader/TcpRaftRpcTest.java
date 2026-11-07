package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;

class TcpRaftRpcTest {
	final Vertx vertx = Vertx.vertx();
	final Context context = vertx.getOrCreateContext();
	final List<TcpRaftRpc> rpcs = new ArrayList<>();
	final List<io.vertx.core.net.NetClient> rawClients = new ArrayList<>();

	@AfterEach void shutdown() throws Exception {
		for (var rpc : rpcs) await(ContextDispatch.call(context, rpc::close));
		for (var client : rawClients) await(client.close());
		await(vertx.close());
	}

	static final class Raw {
		final NetSocket socket;
		final LinkedBlockingQueue<TcpRaftProtocol.Frame> frames = new LinkedBlockingQueue<>();
		final CountDownLatch closed = new CountDownLatch(1);
		Raw(NetSocket socket) {
			this.socket = socket;
			var parser = RecordParser.newFixed(4);
			var length = new AtomicInteger();
			parser.handler(bytes -> {
				if (length.get() == 0) { length.set(bytes.getInt(0)); parser.fixedSizeMode(length.get()); }
				else {
					length.set(0); parser.fixedSizeMode(4);
					try { frames.add(TcpRaftProtocol.decode(bytes)); } catch (IOException e) { throw new AssertionError(e); }
				}
			});
			socket.handler(parser).exceptionHandler(ignored -> {}).closeHandler(ignored -> closed.countDown());
			socket.resume();
		}
		TcpRaftProtocol.Frame next() throws Exception {
			var frame = frames.poll(5, TimeUnit.SECONDS); assertNotNull(frame, "Expected TCP frame"); return frame;
		}
		void hello(String id) throws Exception {
			await(socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.HELLO, 0, 0, 0,
				RaftWireCodec.identity(new RaftIdentity(id, "group"), null))));
		}
		void reply(TcpRaftProtocol.Frame request, String nodeId) throws Exception {
			await(socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.RESPONSE, request.operation(), request.requestId(), 200,
				RaftWireCodec.identity(new RaftIdentity(nodeId, "group"), "cluster"))));
		}
	}

	TcpRaftRpc rpc(String id, int limit, long timeout) throws Exception {
		var rpc = await(ContextDispatch.call(context, () -> Future.succeededFuture(new TcpRaftRpc(context,
			new RaftIdentity(id, "group"), new NetServerOptions().setHost("127.0.0.1").setPort(0),
			new NetClientOptions(), RaftTlsIdentity::nodeId, limit, timeout))));
		rpcs.add(rpc); return rpc;
	}
	Future<RaftWireCodec.PeerIdentity> identify(TcpRaftRpc rpc, int port) {
		return ContextDispatch.call(context, () -> {
			var result = ContextDispatch.<RaftWireCodec.PeerIdentity>promise(context);
			rpc.connection(new NodeEndpoint("127.0.0.1", port, "node-b")).identify(result); return result.future();
		});
	}
	int identityServer(TcpRaftRpc rpc) throws Exception {
		return await(ContextDispatch.call(context, () -> {
			rpc.get("/identity", value -> RaftWireCodec.identity(new RaftIdentity("node-b", "group"), "cluster"),
				(peer, ignored, completion) -> completion.succeed(null), peer -> 0, 503);
			return rpc.listen();
		}));
	}
	Raw connect(int port) throws Exception {
		var client = vertx.createNetClient(); rawClients.add(client);
		return await(client.connect(port, "127.0.0.1").map(Raw::new));
	}

	@Test void fragmentedAndCoalescedFramesRetainRequestCorrelation() throws Exception {
		var server = rpc("node-b", 8, 3000); int port = identityServer(server);
		var raw = connect(port); assertEquals(TcpRaftProtocol.HELLO, raw.next().kind());
		var hello = TcpRaftProtocol.frame(TcpRaftProtocol.HELLO, 0, 0, 0,
			RaftWireCodec.identity(new RaftIdentity("node-a", "group"), null));
		for (int i = 0; i < hello.length(); i++) await(raw.socket.write(hello.getBuffer(i, i + 1)));
		var combined = TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST, 0, 17, 0, null)
			.appendBuffer(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST, 0, 23, 0, null));
		await(raw.socket.write(combined));
		var first = raw.next(); var second = raw.next();
		assertEquals(java.util.Set.of(17L, 23L), java.util.Set.of(first.requestId(), second.requestId()));
		for (var frame : List.of(first, second)) {
			assertEquals(200, frame.status()); assertEquals("node-b", RaftWireCodec.identity(frame.body()).nodeId());
		}
	}

	@Test void invalidLengthsVersionsAndRequestsBeforeHandshakeCloseTheSocket() throws Exception {
		int port = identityServer(rpc("node-b", 8, 3000));
		var badVersion = TcpRaftProtocol.frame(TcpRaftProtocol.HELLO, 0, 0, 0, null).setByte(8, (byte) 99);
		for (var bytes : List.of(Buffer.buffer().appendInt(-1),
			Buffer.buffer().appendInt(TcpRaftProtocol.MAX_FRAME_BYTES + 1), badVersion,
			TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST, 0, 1, 0, null))) {
			var raw = connect(port); raw.next(); await(raw.socket.write(bytes));
			assertTrue(raw.closed.await(5, TimeUnit.SECONDS));
		}
	}

	@Test void partialFrameHasADeadlineAndDoesNotPreventOtherPeers() throws Exception {
		int port = identityServer(rpc("node-b", 8, 300));
		var stalled = connect(port); stalled.next(); stalled.hello("node-a");
		await(stalled.socket.write(Buffer.buffer().appendInt(100).appendByte((byte) 0)));
		assertTrue(stalled.closed.await(5, TimeUnit.SECONDS));
		assertEquals("node-b", await(identify(rpc("node-a", 8, 3000), port)).nodeId());
	}

	@Test void timeoutAndLateResponseCompleteOnceAndReusePersistentConnection() throws Exception {
		var accepted = Promise.<Raw>promise();
		var server = await(vertx.createNetServer().connectHandler(socket -> accepted.tryComplete(new Raw(socket)))
			.listen(0, "127.0.0.1"));
		var rpc = rpc("node-a", 1, 700);
		var completions = new AtomicInteger(); var result = Promise.<RaftWireCodec.PeerIdentity>promise();
		context.runOnContext(ignored -> rpc.connection(new NodeEndpoint("127.0.0.1", server.actualPort(), "node-b"))
			.identify((value, failure) -> {
				completions.incrementAndGet();
				if (Vertx.currentContext() != context) result.tryFail("Wrong owner context: " + Vertx.currentContext());
				else result.complete(value, failure);
			}));
		var raw = await(accepted.future()); raw.next(); raw.hello("node-b"); var held = raw.next();
		assertThrows(Exception.class, () -> await(result.future()));
		assertInstanceOf(TimeoutException.class, result.future().cause());
		var next = identify(rpc, server.actualPort()); var request = raw.next();
		raw.reply(held, "node-b"); raw.reply(request, "node-b");
		assertEquals("node-b", await(next).nodeId()); assertEquals(1, completions.get());
	}

	@Test void disconnectFailsPendingRequestAndNextOperationReconnectsImmediately() throws Exception {
		var connections = new LinkedBlockingQueue<Raw>();
		var server = await(vertx.createNetServer().connectHandler(socket -> connections.add(new Raw(socket)))
			.listen(0, "127.0.0.1"));
		var rpc = rpc("node-a", 8, 3000);
		var first = identify(rpc, server.actualPort()); var raw = connections.poll(5, TimeUnit.SECONDS);
		assertNotNull(raw); raw.next(); raw.hello("node-b"); raw.next(); await(raw.socket.close());
		assertThrows(Exception.class, () -> await(first));
		// An established socket closing must not penalize the first reconnect attempt.
		var next = identify(rpc, server.actualPort()); var recovered = connections.poll(5, TimeUnit.SECONDS);
		assertNotNull(recovered); recovered.next(); recovered.hello("node-b"); recovered.reply(recovered.next(), "node-b");
		assertEquals("node-b", await(next).nodeId());
	}

	@Test void failedHandshakeStillBacksOffBeforeAnotherConnectionAttempt() throws Exception {
		var accepted = Promise.<Raw>promise(); var connections = new AtomicInteger();
		var server = await(vertx.createNetServer().connectHandler(socket -> {
			connections.incrementAndGet(); accepted.tryComplete(new Raw(socket));
		}).listen(0, "127.0.0.1"));
		var rpc = rpc("node-a", 8, 3000);
		var first = Promise.<RaftWireCodec.PeerIdentity>promise();
		var next = Promise.<RaftWireCodec.PeerIdentity>promise();
		context.runOnContext(ignored -> {
			var connection = rpc.connection(new NodeEndpoint("127.0.0.1", server.actualPort(), "node-b"));
			connection.identify((value, failure) -> {
				first.complete(value, failure);
				connection.identify(next);
			});
		});
		var raw = await(accepted.future()); raw.next();
		await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.HELLO, 0, 0, 0,
			RaftWireCodec.identity(new RaftIdentity("node-b", "foreign-group"), null))));
		assertThrows(Exception.class, () -> await(first.future()));
		assertInstanceOf(IOException.class, first.future().cause());
		assertThrows(Exception.class, () -> await(next.future()));
		assertInstanceOf(RaftUnavailableException.class, next.future().cause());
		assertTrue(next.future().cause().getMessage().contains("backoff"));
		assertEquals(1, connections.get());
	}

	@Test void oversizedResponseFailsRequestWithoutLeakingItsCapacity() throws Exception {
		var accepted = Promise.<Raw>promise();
		var server = await(vertx.createNetServer().connectHandler(socket -> accepted.tryComplete(new Raw(socket)))
			.listen(0, "127.0.0.1"));
		var rpc = rpc("node-a", 1, 3000); var request = identify(rpc, server.actualPort());
		var raw = await(accepted.future()); raw.next(); raw.hello("node-b"); raw.next();
		await(raw.socket.write(Buffer.buffer().appendInt(TcpRaftProtocol.MAX_FRAME_BYTES + 1)));
		assertThrows(Exception.class, () -> await(request));
		assertTrue(request.cause().getMessage().contains("length"));
		await(ContextDispatch.call(context, rpc::close));
		var after = identify(rpc, server.actualPort()); assertThrows(Exception.class, () -> await(after));
		assertInstanceOf(java.util.concurrent.CancellationException.class, after.cause());
	}

	@Test void expiredCodecWorkRetainsCapacityAndCloseDrainsWorker() throws Exception {
		var rpc = rpc("node-a", 1, 150);
		var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
		var result = Promise.<Void>promise();
		try {
			context.runOnContext(ignored -> rpc.request(new NodeEndpoint("127.0.0.1", 9, "node-b"), "/request-vote", () -> {
				entered.countDown();
				if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Gate timeout");
				return Buffer.buffer();
			}, (bytes, peer) -> null, true, result));
			assertTrue(entered.await(5, TimeUnit.SECONDS)); assertThrows(Exception.class, () -> await(result.future()));
			assertInstanceOf(TimeoutException.class, result.future().cause());
			var overload = identify(rpc, 9); assertThrows(Exception.class, () -> await(overload));
			assertInstanceOf(RaftUnavailableException.class, overload.cause());
			var close = ContextDispatch.call(context, rpc::close);
			await(ContextDispatch.call(context, () -> Future.succeededFuture())); assertFalse(close.isComplete());
			release.countDown(); await(close);
		} finally { release.countDown(); }
	}

	@Test void bulkTrafficUsesSeparateSocketFromControlRequests() throws Exception {
		var connections = new LinkedBlockingQueue<Raw>();
		var server = await(vertx.createNetServer().connectHandler(socket -> connections.add(new Raw(socket)))
			.listen(0, "127.0.0.1"));
		var rpc = rpc("node-a", 8, 3000); var bulk = Promise.<Void>promise();
		context.runOnContext(ignored -> rpc.request(new NodeEndpoint("127.0.0.1", server.actualPort(), "node-b"),
			"/install-snapshot", () -> Buffer.buffer(new byte[20000]), (bytes, peer) -> null, true, bulk));
		var rawBulk = connections.poll(5, TimeUnit.SECONDS); assertNotNull(rawBulk);
		rawBulk.next(); rawBulk.hello("node-b"); rawBulk.next();
		var control = identify(rpc, server.actualPort());
		var rawControl = connections.poll(5, TimeUnit.SECONDS); assertNotNull(rawControl);
		rawControl.next(); rawControl.hello("node-b"); rawControl.reply(rawControl.next(), "node-b");
		assertEquals("node-b", await(control).nodeId()); assertFalse(bulk.future().isComplete());
		await(ContextDispatch.call(context, rpc::close)); assertThrows(Exception.class, () -> await(bulk.future()));
	}

	@Test void errorStatusesFailOnlyTheirExchangeAndReleaseCapacity() throws Exception {
		var accepted = Promise.<Raw>promise();
		var server = await(vertx.createNetServer().connectHandler(socket -> accepted.tryComplete(new Raw(socket)))
			.listen(0, "127.0.0.1"));
		var rpc = rpc("node-a", 1, 3000);
		for (int status : List.of(425, 429, 503, 400, 409, 500)) {
			var result = identify(rpc, server.actualPort()); var raw = await(accepted.future());
			if (status == 425) { raw.next(); raw.hello("node-b"); }
			var request = raw.next();
			await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.RESPONSE,
				request.operation(), request.requestId(), status, null)));
			assertThrows(Exception.class, () -> await(result));
			if (status == 425 || status == 429 || status == 503) assertInstanceOf(RaftUnavailableException.class, result.cause());
			else assertInstanceOf(IOException.class, result.cause());
			assertTrue(result.cause().getMessage().contains(Integer.toString(status)));
		}
		var recovered = identify(rpc, server.actualPort()); var raw = await(accepted.future());
		raw.reply(raw.next(), "node-b"); assertEquals("node-b", await(recovered).nodeId());
	}

	@Test void mismatchedResponseOperationClosesSessionAndFailsEveryPendingExchange() throws Exception {
		var connections = new LinkedBlockingQueue<Raw>();
		var server = await(vertx.createNetServer().connectHandler(socket -> connections.add(new Raw(socket)))
			.listen(0, "127.0.0.1"));
		var rpc = rpc("node-a", 2, 3000);
		var first = identify(rpc, server.actualPort()); var second = identify(rpc, server.actualPort());
		var raw = connections.poll(5, TimeUnit.SECONDS); assertNotNull(raw);
		raw.next(); raw.hello("node-b"); var request = raw.next(); raw.next();
		await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.RESPONSE,
			TcpRaftProtocol.operation("/membership"), request.requestId(), 200, null)));
		for (var result : List.of(first, second)) {
			assertThrows(Exception.class, () -> await(result));
			assertInstanceOf(IOException.class, result.cause());
			assertTrue(result.cause().getMessage().contains("response mismatch"));
		}
		assertTrue(raw.closed.await(5, TimeUnit.SECONDS));
		var recovered = identify(rpc, server.actualPort());
		var fresh = connections.poll(5, TimeUnit.SECONDS); assertNotNull(fresh);
		fresh.next(); fresh.hello("node-b"); fresh.reply(fresh.next(), "node-b");
		assertEquals("node-b", await(recovered).nodeId());
	}

	@Test void expectedIdentityMismatchCannotDispatchRequestAndDoesNotPoisonVerifiedSession() throws Exception {
		var calls = new AtomicInteger(); var server = rpc("node-c", 8, 3000);
		int port = await(ContextDispatch.call(context, () -> {
			server.get("/identity", value -> RaftWireCodec.identity(new RaftIdentity("node-c", "group"), null),
				(peer, ignored, completion) -> { calls.incrementAndGet(); completion.succeed(null); }, peer -> 0, 503);
			return server.listen();
		}));
		var rpc = rpc("node-a", 1, 3000); var wrong = identify(rpc, port);
		assertThrows(Exception.class, () -> await(wrong));
		assertInstanceOf(IOException.class, wrong.cause()); assertEquals(0, calls.get());
		var correct = ContextDispatch.call(context, () -> {
			var result = ContextDispatch.<RaftWireCodec.PeerIdentity>promise(context);
			rpc.connection(new NodeEndpoint("127.0.0.1", port, "node-c")).identify(result); return result.future();
		});
		assertEquals("node-c", await(correct).nodeId()); assertEquals(1, calls.get());
	}

	@Test void malformedResponseAndThrowingEncoderReleaseOutgoingCapacity() throws Exception {
		var accepted = Promise.<Raw>promise();
		var server = await(vertx.createNetServer().connectHandler(socket -> accepted.tryComplete(new Raw(socket)))
			.listen(0, "127.0.0.1"));
		var rpc = rpc("node-a", 1, 3000);
		var failure = new IOException("Cannot encode request");
		for (boolean bulk : List.of(false, true)) {
			var result = ContextDispatch.call(context, () -> {
				var completion = ContextDispatch.<Void>promise(context);
				rpc.request(new NodeEndpoint("127.0.0.1", server.actualPort(), "node-b"), "/request-vote",
					() -> { throw failure; }, (bytes, peer) -> null, bulk, completion);
				return completion.future();
			});
			assertThrows(Exception.class, () -> await(result)); assertSame(failure, result.cause());
		}
		var malformed = identify(rpc, server.actualPort()); var raw = await(accepted.future());
		raw.next(); raw.hello("node-b"); var request = raw.next();
		await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.RESPONSE,
			request.operation(), request.requestId(), 200, Buffer.buffer("not an identity"))));
		assertThrows(Exception.class, () -> await(malformed)); assertInstanceOf(IOException.class, malformed.cause());
		var recovered = identify(rpc, server.actualPort()); raw.reply(raw.next(), "node-b");
		assertEquals("node-b", await(recovered).nodeId());
	}

	@Test void unknownRejectedAndInvalidReadRequestsNeverReachPolicy() throws Exception {
		var calls = new AtomicInteger(); var server = rpc("node-b", 1, 3000);
		int port = await(ContextDispatch.call(context, () -> {
			server.get("/identity", value -> Buffer.buffer("ready"),
				(peer, ignored, completion) -> { calls.incrementAndGet(); completion.succeed(null); }, peer -> 0, 500);
			server.get("/membership", value -> Buffer.buffer(),
				(peer, ignored, completion) -> fail("Rejected request reached policy"), peer -> 425, 500);
			return server.listen();
		}));
		var raw = connect(port); raw.next(); raw.hello("node-a");
		await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST,
			TcpRaftProtocol.operation("/bootstrap"), 1, 0, null)));
		assertEquals(404, raw.next().status());
		await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST,
			TcpRaftProtocol.operation("/membership"), 2, 0, null)));
		assertEquals(425, raw.next().status());
		await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST, 0, 3, 0, Buffer.buffer("unexpected"))));
		assertEquals(400, raw.next().status()); assertEquals(0, calls.get());
		await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST, 0, 4, 0, null)));
		assertEquals(200, raw.next().status()); assertEquals(1, calls.get());
	}

	@Test void timedOutIncomingHandlerRetainsCapacityUntilCompletionAndIgnoresDuplicateCompletion() throws Exception {
		var held = Promise.<RaftRequestCompletion<String>>promise(); var server = rpc("node-b", 1, 500);
		int port = await(ContextDispatch.call(context, () -> {
			server.<String>get("/membership", Buffer::buffer,
				(peer, ignored, completion) -> held.complete(completion), peer -> 0, 500);
			server.get("/identity", value -> Buffer.buffer("ready"),
				(peer, ignored, completion) -> completion.succeed(null), peer -> 0, 500);
			return server.listen();
		}));
		var raw = connect(port); raw.next(); raw.hello("node-a");
		await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST, 1, 1, 0, null)));
		var completion = await(held.future()); assertEquals(503, raw.next().status());
		assertFalse(await(ContextDispatch.call(context, () -> Future.succeededFuture(completion.isActive()))));
		await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST, 0, 2, 0, null)));
		assertEquals(429, raw.next().status());
		await(ContextDispatch.call(context, () -> {
			completion.succeed("late"); completion.succeed("duplicate"); return Future.succeededFuture();
		}));
		await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST, 0, 3, 0, null)));
		var response = raw.next(); assertEquals(3, response.requestId()); assertEquals(200, response.status());
	}

	@Test void duplicateInFlightRequestIdClosesSocketWithoutDispatchingTwice() throws Exception {
		var held = Promise.<RaftRequestCompletion<Void>>promise(); var calls = new AtomicInteger();
		var server = rpc("node-b", 8, 3000);
		int port = await(ContextDispatch.call(context, () -> {
			server.<Void>get("/identity", null, (peer, ignored, completion) -> {
				calls.incrementAndGet(); held.complete(completion);
			}, peer -> 0, 500);
			return server.listen();
		}));
		var raw = connect(port); raw.next(); raw.hello("node-a");
		var request = TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST, 0, 17, 0, null);
		await(raw.socket.write(request)); var completion = await(held.future());
		await(raw.socket.write(request)); assertTrue(raw.closed.await(5, TimeUnit.SECONDS));
		assertEquals(1, calls.get());
		assertFalse(await(ContextDispatch.call(context, () -> Future.succeededFuture(completion.isActive()))));
		completion.succeed(null);
	}

	@Test void decoderPolicyAndResponseEncoderFailuresReleaseIncomingCapacity() throws Exception {
		var calls = new AtomicInteger(); var server = rpc("node-b", 1, 3000);
		int port = await(ContextDispatch.call(context, () -> {
			server.<String, String>post("/join", bytes -> { throw new IOException("Invalid join"); }, Buffer::buffer,
				(peer, message, completion) -> fail("Invalid body reached policy"), peer -> 0, 409);
			server.<String>get("/membership", Buffer::buffer,
				(peer, ignored, completion) -> { throw new IllegalStateException("Policy failed"); }, peer -> 0, 503);
			server.get("/bootstrap", value -> { throw new IOException("Cannot encode genesis"); },
				(peer, ignored, completion) -> completion.succeed(null), peer -> 0, 500);
			server.get("/identity", value -> Buffer.buffer("ready"),
				(peer, ignored, completion) -> { calls.incrementAndGet(); completion.succeed(null); }, peer -> 0, 500);
			return server.listen();
		}));
		var raw = connect(port); raw.next(); raw.hello("node-a");
		long id = 0;
		for (var operation : List.of("/join", "/membership", "/bootstrap")) {
			await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST,
				TcpRaftProtocol.operation(operation), ++id, 0, Buffer.buffer())));
			assertEquals(switch (operation) { case "/join" -> 409; case "/membership" -> 503; default -> 500; },
				raw.next().status());
			await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST, 0, ++id, 0, null)));
			assertEquals(200, raw.next().status());
		}
		assertEquals(3, calls.get());
	}

	@Test void closeDrainsAcceptedIncomingDecoderWithoutDispatchingExpiredRequest() throws Exception {
		var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
		var server = rpc("node-b", 1, 500);
		int port = await(ContextDispatch.call(context, () -> {
			server.<String, String>post("/join", bytes -> {
				entered.countDown();
				if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Gate timed out");
				return bytes.toString();
			}, Buffer::buffer, (peer, message, completion) -> {
				calls.incrementAndGet(); completion.succeed(message);
			}, peer -> 0, 409);
			return server.listen();
		}));
		try {
			var raw = connect(port); raw.next(); raw.hello("node-a");
			await(raw.socket.write(TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST,
				TcpRaftProtocol.operation("/join"), 1, 0, Buffer.buffer(new byte[16385]))));
			assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(503, raw.next().status());
			var closing = ContextDispatch.call(context, server::close);
			await(ContextDispatch.call(context, () -> Future.succeededFuture()));
			assertFalse(closing.isComplete(), "Close must drain accepted codec work");
			release.countDown(); await(closing); assertEquals(0, calls.get());
		} finally { release.countDown(); }
	}
}
