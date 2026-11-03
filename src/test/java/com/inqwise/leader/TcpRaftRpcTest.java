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
}
