package com.inqwise.leader;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.vertx.core.Completable;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;

/** Owner-context bounded RPC exchanges over persistent TCP sockets. No operation is transparently replayed. */
final class TcpRaftRpc implements RaftRpcClient, RaftRpcServer {
	@FunctionalInterface interface BodyEncoder { Buffer encode() throws Exception; }
	@FunctionalInterface interface BodyDecoder<T> { T decode(Buffer bytes, String peer) throws Exception; }
	private record Route<T, R>(Decoder<T> decoder, Encoder<R> encoder, RequestHandler<T, R> handler,
		ToIntFunction<String> rejection, int failureStatus) {}
	private record Address(String host, int port, boolean bulk) {}
	private static final Logger LOG = LogManager.getLogger(TcpRaftRpc.class);
	private static final int PER_CONNECTION_LIMIT = 16;
	private static final int MAX_CONNECTIONS = 256;
	private static final int MAX_ROUTES = 128;
	private static final long MAX_QUEUED_BYTES = 8L * TcpRaftProtocol.MAX_FRAME_BYTES;
	private final Context context;
	private final RaftIdentity identity;
	private final NetServer server;
	private final NetClient client;
	private final Function<X509Certificate, String> certificateIdentity;
	private final boolean tls;
	private final int limit;
	private final long timeoutMillis;
	private final Map<Integer, Route<?, ?>> routes = new HashMap<>();
	private final Map<Address, Dial> dials = new LinkedHashMap<>(16, 0.75f, true);
	private final Set<Session> sessions = new HashSet<>();
	private final Map<Long, Exchange<?>> pending = new HashMap<>();
	private final Set<Incoming<?, ?>> incoming = new HashSet<>();
	private Future<Integer> listening;
	private Promise<Void> closing;
	private boolean serverClosed;
	private boolean clientClosed;
	private Throwable closeFailure;
	private long sequence;
	private long queuedBytes;
	private int workers;
	private int connecting;
	private int closingSockets;

	TcpRaftRpc(Context context, RaftIdentity identity, NetServerOptions serverOptions, NetClientOptions clientOptions,
		Function<X509Certificate, String> certificateIdentity, int limit, long timeoutMillis) {
		this.context = context; this.identity = identity; this.certificateIdentity = certificateIdentity;
		this.limit = limit; this.timeoutMillis = timeoutMillis; this.tls = serverOptions.isSsl();
		client = context.owner().createNetClient(clientOptions);
		server = context.owner().createNetServer(serverOptions).connectHandler(socket -> {
			socket.pause();
			onOwner(() -> {
				if (closing != null || sessions.size() + connecting >= MAX_CONNECTIONS) { closeSocket(socket); return; }
				new Session(socket, null);
			});
		});
	}

	@Override public RaftConnection connection(NodeEndpoint endpoint) { return new TcpRaftConnection(this, endpoint); }

	@Override public <R> void get(String operation, Encoder<R> encoder, RequestHandler<Void, R> handler,
		ToIntFunction<String> rejection, int failureStatus) {
		register(operation, new Route<>(null, encoder, handler, rejection, failureStatus));
	}
	@Override public <T, R> void post(String operation, Decoder<T> decoder, Encoder<R> encoder,
		RequestHandler<T, R> handler, ToIntFunction<String> rejection, int failureStatus) {
		register(operation, new Route<>(decoder, encoder, handler, rejection, failureStatus));
	}
	private void register(String operation, Route<?, ?> route) {
		if (listening != null || closing != null) throw new IllegalStateException("Register handlers before listen");
		if (routes.putIfAbsent(TcpRaftProtocol.operation(operation), route) != null) {
			throw new IllegalArgumentException("Duplicate operation");
		}
	}

	@Override public Future<Integer> listen() {
		if (closing != null) return Future.failedFuture(new IllegalStateException("Listener closed"));
		if (listening == null) listening = server.listen().map(NetServer::actualPort);
		return listening;
	}
	@Override public int actualPort() { return server.actualPort(); }

	<T> void request(NodeEndpoint endpoint, String operation, BodyEncoder encoder, BodyDecoder<T> decoder,
		boolean bulk, Completable<T> completion) {
		Objects.requireNonNull(completion, "completion");
		if (closing != null || pending.size() >= limit) {
			completion.fail(closing != null ? new CancellationException("Transport closed")
				: new RaftUnavailableException("Transport request limit reached")); return;
		}
		// IDs are scoped to this runtime; do not reuse an ID for late replies on a live socket.
		if (sequence == Long.MAX_VALUE) { completion.fail(new IOException("TCP request IDs exhausted")); return; }
		var exchange = new Exchange<T>(++sequence, endpoint, TcpRaftProtocol.operation(operation), decoder, completion);
		pending.put(exchange.id, exchange);
		exchange.timer = context.owner().setTimer(timeoutMillis,
			ignored -> exchange.finish(null, new TimeoutException("TCP dispatch deadline exceeded")));
		if (bulk) {
			exchange.working = true; workers++;
			context.executeBlocking(encoder::encode, false).onComplete(result -> {
				exchange.working = false; workers--;
				if (result.failed()) exchange.finish(null, result.cause()); else exchange.send(result.result(), true);
				exchange.release(); completeClose();
			});
		} else {
			try { exchange.send(encoder.encode(), false); } catch (Exception e) { exchange.finish(null, e); }
		}
	}

	private Dial dial(Address address) {
		var dial = dials.get(address);
		if (dial != null) return dial;
		if (dials.size() >= MAX_ROUTES) {
			// Evict the least recently used idle route, including idle sockets after endpoint churn.
			var victim = dials.entrySet().stream().filter(e -> e.getValue().idle())
				.map(Map.Entry::getKey).findFirst();
			if (victim.isEmpty()) throw new RaftUnavailableException("TCP route limit reached");
			var removed = dials.remove(victim.get());
			if (removed.session != null) removed.session.fail(new CancellationException("TCP idle route evicted"));
		}
		dial = new Dial(address); dials.put(address, dial); return dial;
	}

	private final class Dial {
		final Address address;
		Session session;
		Future<Session> connection;
		long retryAfter;
		int failures;
		Dial(Address address) { this.address = address; }

		Future<Session> connect() {
			if (session != null && !session.closed) return session.ready.future();
			if (connection != null) return connection;
			if (retryAfter != 0 && System.nanoTime() - retryAfter < 0) return Future.failedFuture(new RaftUnavailableException("TCP reconnect backoff"));
			if (sessions.size() + connecting >= MAX_CONNECTIONS) {
				return Future.failedFuture(new RaftUnavailableException("TCP connection limit reached"));
			}
			connecting++;
			Promise<Session> result = ContextDispatch.promise(context);
			connection = result.future();
			client.connect(address.port(), address.host()).onComplete(connected -> {
				connecting--; connection = null;
				if (connected.failed()) { failed(); result.tryFail(connected.cause()); }
				else if (closing != null) {
					closeSocket(connected.result()); result.tryFail(new CancellationException("Transport closed"));
				} else {
					session = new Session(connected.result(), this);
					session.ready.future().onComplete(result);
				}
				completeClose();
			});
			return result.future();
		}
		boolean idle() {
			return connection == null && (session == null || session.ready.future().succeeded()
				&& session.queued == 0 && pending.values().stream().noneMatch(e -> e.session == session));
		}
		void failed() {
			long delay = Math.min(2000, 50L << failures);
			failures = Math.min(failures + 1, 6);
			retryAfter = System.nanoTime() + (delay + ThreadLocalRandom.current().nextLong(delay / 2 + 1)) * 1_000_000L;
		}
	}

	private final class Session {
		final NetSocket socket;
		final Dial dial;
		final Promise<Session> ready = ContextDispatch.promise(context);
		final RecordParser parser = RecordParser.newFixed(4);
		final Set<Long> requests = new HashSet<>();
		String certificatePeer;
		String peer;
		boolean closed;
		int length;
		int buffered;
		long readTimer = -1;
		long helloTimer = -1;
		long queued;

		Session(NetSocket socket, Dial dial) {
			this.socket = socket; this.dial = dial;
			sessions.add(this);
			socket.pause().setWriteQueueMaxSize(2 * TcpRaftProtocol.MAX_FRAME_BYTES);
			socket.exceptionHandler(cause -> onOwner(() -> fail(cause)));
			socket.closeHandler(ignored -> onOwner(() -> fail(new IOException("TCP peer disconnected"))));
			parser.handler(this::record);
			parser.exceptionHandler(this::fail);
			socket.handler(chunk -> {
				socket.pause();
				onOwner(() -> {
					if (closed) return;
					try {
						buffered += chunk.length(); parser.handle(chunk);
						if (!closed && (buffered > 0 || length != 0)) {
							if (readTimer == -1) readTimer = context.owner().setTimer(timeoutMillis,
								ignored -> fail(new TimeoutException("TCP frame deadline exceeded")));
						} else { cancelTimer(readTimer); readTimer = -1; }
					} catch (Exception e) { fail(e); }
					if (!closed) socket.resume();
				});
			});
			try {
				if (tls) certificatePeer = RaftTlsIdentity.verify(socket, identity.nodeId(), certificateIdentity);
				else if (socket.isSsl()) throw new IOException("Unexpected TLS connection");
				helloTimer = context.owner().setTimer(timeoutMillis, ignored -> fail(new TimeoutException("TCP identity deadline exceeded")));
				write(TcpRaftProtocol.frame(TcpRaftProtocol.HELLO, 0, 0, 0,
					RaftWireCodec.identity(identity, null))).onFailure(this::fail);
				socket.resume();
			} catch (Exception e) { fail(e); }
		}

		void record(Buffer bytes) {
			if (closed) return;
			buffered -= bytes.length();
			try {
				if (length == 0) {
					length = bytes.getInt(0);
					if (length < TcpRaftProtocol.HEADER_BYTES || length > TcpRaftProtocol.MAX_FRAME_BYTES) {
						throw new IOException("Invalid TCP frame length");
					}
					parser.fixedSizeMode(length); return;
				}
				length = 0; parser.fixedSizeMode(4);
				var frame = TcpRaftProtocol.decode(bytes);
				if (peer == null) {
					if (frame.kind() != TcpRaftProtocol.HELLO) throw new IOException("TCP identity handshake required");
					var hello = RaftWireCodec.identity(frame.body());
					if (!identity.groupName().equals(hello.groupName())
						|| certificatePeer != null && !certificatePeer.equals(hello.nodeId())) {
						throw new IOException("TCP certificate identity or group mismatch");
					}
					peer = hello.nodeId(); cancelTimer(helloTimer);
					if (dial != null) { dial.failures = 0; dial.retryAfter = 0; }
					ready.tryComplete(this); return;
				}
				if (frame.kind() == TcpRaftProtocol.RESPONSE) {
					if (dial == null) throw new IOException("Unexpected TCP response");
					var exchange = pending.get(frame.requestId());
					// Timed-out IDs can receive a late reply; never apply it to another exchange.
					if (exchange != null && exchange.session == this) exchange.receive(frame);
				} else if (frame.kind() == TcpRaftProtocol.REQUEST) {
					if (dial != null) throw new IOException("Unexpected TCP request");
					receive(frame);
				} else throw new IOException("Duplicate TCP handshake");
			} catch (Exception e) { fail(e); }
		}

		void receive(TcpRaftProtocol.Frame frame) throws Exception {
			if (requests.contains(frame.requestId())) throw new IOException("Duplicate TCP request ID");
			var route = routes.get(frame.operation());
			int rejected = incoming.size() >= limit || requests.size() >= PER_CONNECTION_LIMIT ? 429
				: route == null ? 404 : route.rejection().applyAsInt(peer);
			if (rejected != 0) { respond(frame, rejected, null); return; }
			requests.add(frame.requestId());
			new Incoming<>(this, frame, route).start();
		}

		void respond(TcpRaftProtocol.Frame frame, int status, Buffer body) {
			if (closed) return;
			try { write(TcpRaftProtocol.frame(TcpRaftProtocol.RESPONSE, frame.operation(), frame.requestId(), status, body))
				.onFailure(this::fail); } catch (Exception e) { fail(e); }
		}

		Future<Void> write(Buffer bytes) {
			if (closed) return Future.failedFuture(new IOException("TCP connection closed"));
			if (socket.writeQueueFull() || queued + bytes.length() > 2L * TcpRaftProtocol.MAX_FRAME_BYTES
				|| queuedBytes + bytes.length() > MAX_QUEUED_BYTES) {
				return Future.failedFuture(new RaftUnavailableException("TCP write queue limit reached"));
			}
			queued += bytes.length(); queuedBytes += bytes.length();
			return ContextDispatch.relay(context, socket.write(bytes)).andThen(ignored -> { queued -= bytes.length(); queuedBytes -= bytes.length(); });
		}

		void fail(Throwable cause) {
			if (closed) return;
			closed = true; sessions.remove(this);
			cancelTimer(helloTimer); cancelTimer(readTimer);
			socket.handler(null);
			if (dial != null) {
				dial.session = null;
				// Losing a verified connection permits an immediate new attempt. Only failed connection/
				// handshake attempts incur backoff; closing an established socket is not such an attempt.
				if (!ready.future().succeeded() && closing == null) dial.failed();
			}
			ready.tryFail(cause);
			for (var exchange : List.copyOf(pending.values())) if (exchange.session == this) exchange.finish(null, cause);
			for (var request : List.copyOf(incoming)) if (request.session == this) request.finish(503, null);
			closeSocket(socket);
		}
	}

	private final class Exchange<T> {
		final long id;
		final NodeEndpoint endpoint;
		final int operation;
		BodyDecoder<T> decoder;
		Completable<T> completion;
		Session session;
		long timer;
		boolean working;
		boolean responding;

		Exchange(long id, NodeEndpoint endpoint, int operation, BodyDecoder<T> decoder, Completable<T> completion) {
			this.id = id; this.endpoint = endpoint; this.operation = operation; this.decoder = decoder; this.completion = completion;
		}
		void send(Buffer body, boolean bulk) {
			if (completion == null) return;
			try {
				var frame = TcpRaftProtocol.frame(TcpRaftProtocol.REQUEST, operation, id, 0, body);
				dial(new Address(endpoint.host(), endpoint.port(), bulk)).connect().onComplete(connected -> {
					if (completion == null) return;
					if (connected.failed()) { finish(null, connected.cause()); return; }
					session = connected.result();
					if (endpoint.expectedNodeId() != null && !endpoint.expectedNodeId().equals(session.peer)) {
						finish(null, new IOException("TCP peer identity mismatch")); return;
					}
					long count = pending.values().stream().filter(e -> e.session == session && e.completion != null).count();
					if (count > PER_CONNECTION_LIMIT) { finish(null, new RaftUnavailableException("TCP peer request limit reached")); return; }
					session.write(frame).onFailure(cause -> finish(null, cause));
				});
			} catch (Exception e) { finish(null, e); }
		}
		void receive(TcpRaftProtocol.Frame frame) {
			if (completion == null) return;
			if (responding || frame.operation() != operation) { session.fail(new IOException("TCP response mismatch")); return; }
			responding = true;
			int expected = TcpRaftProtocol.read(operation) || operation == 4 || operation == 5 ? 200 : 202;
			if (frame.status() != expected) {
				finish(null, frame.status() == 425 || frame.status() == 429 || frame.status() == 503
					? new RaftUnavailableException("Peer TCP status " + frame.status())
					: new IOException("Peer TCP status " + frame.status())); return;
			}
			var reader = decoder;
			String peer = session.peer;
			if (frame.body().length() > 16384) {
				working = true; workers++;
				context.executeBlocking(() -> reader.decode(frame.body(), peer), false).onComplete(result -> {
					working = false; workers--; finish(result.result(), result.cause()); release(); completeClose();
				});
			} else {
				try { finish(reader.decode(frame.body(), peer), null); } catch (Exception e) { finish(null, e); }
			}
		}
		void finish(T value, Throwable failure) {
			if (completion == null) return;
			var callback = completion; completion = null; decoder = null;
			cancelTimer(timer); release();
			try { callback.complete(value, failure); } catch (Exception e) { LOG.warn("Raft connection completion failed", e); }
		}
		void release() { if (completion == null && !working) pending.remove(id); }
	}

	private final class Incoming<T, R> implements RaftRequestCompletion<R> {
		final Session session;
		final TcpRaftProtocol.Frame frame;
		final Route<T, R> route;
		boolean active = true;
		boolean working;
		boolean processing;
		long timer;
		Incoming(Session session, TcpRaftProtocol.Frame frame, Route<T, R> route) {
			this.session = session; this.frame = frame; this.route = route;
		}
		@Override public boolean isActive() { return active; }
		void start() {
			incoming.add(this);
			timer = context.owner().setTimer(timeoutMillis, ignored -> finish(503, null));
			if (route.decoder() == null) {
				if (frame.body().length() != 0) finish(400, null); else dispatch(null);
			} else if (frame.body().length() > 16384) {
				working = true; workers++;
				context.executeBlocking(() -> route.decoder().decode(frame.body()), false).onComplete(result -> {
					working = false; workers--;
					if (active) {
						if (result.failed()) finish(route.failureStatus(), null); else dispatch(result.result());
					}
					release(); completeClose();
				});
			} else {
				try { dispatch(route.decoder().decode(frame.body())); } catch (Exception e) { finish(route.failureStatus(), null); }
			}
		}
		void dispatch(T message) {
			if (!active) return;
			processing = true;
			try { route.handler().handle(session.peer, message, this); } catch (Exception e) { complete(null, e); }
		}
		@Override public void complete(R value, Throwable failure) {
			if (Vertx.currentContext() != context) { onOwner(() -> complete(value, failure)); return; }
			if (!processing) return;
			processing = false;
			if (active) {
				if (failure != null) finish(route.failureStatus(), null);
				else if (route.encoder() == null) finish(202, null);
				else {
					// Status responses can carry genesis/configuration; encode off the event loop.
					working = true; workers++;
					context.executeBlocking(() -> route.encoder().encode(value), false).onComplete(result -> {
						working = false; workers--;
						if (active) finish(result.failed() ? route.failureStatus() : 200, result.result());
						release(); completeClose();
					});
				}
			}
			release();
		}
		void finish(int status, Buffer body) {
			if (!active) { release(); return; }
			active = false; cancelTimer(timer);
			session.respond(frame, status, body); release();
		}
		void release() {
			if (!active && !working && (!processing || closing != null)) {
				incoming.remove(this); session.requests.remove(frame.requestId());
			}
		}
	}

	private void cancelTimer(long timer) {
		if (timer >= 0) context.owner().cancelTimer(timer);
	}
	private void onOwner(Runnable operation) {
		if (Vertx.currentContext() == context) operation.run(); else context.runOnContext(ignored -> operation.run());
	}
	private void closeSocket(NetSocket socket) {
		closingSockets++;
		ContextDispatch.relay(context, socket.close()).onComplete(ignored -> { closingSockets--; completeClose(); });
	}
	@Override public Future<Void> close() {
		if (closing == null) {
			closing = ContextDispatch.promise(context);
			for (var exchange : List.copyOf(pending.values())) exchange.finish(null, new CancellationException("Transport closed"));
			for (var request : List.copyOf(incoming)) request.finish(503, null);
			for (var session : List.copyOf(sessions)) session.fail(new CancellationException("Transport closed"));
			var stopped = listening == null ? server.close() : listening.transform(ignored -> server.close());
			stopped.onComplete(result -> { serverClosed = true; closeFailure = result.cause(); completeClose(); });
			client.close().onComplete(result -> {
				clientClosed = true; if (result.failed()) closeFailure = result.cause(); completeClose();
			});
		}
		return closing.future();
	}
	private void completeClose() {
		if (closing != null && serverClosed && clientClosed && connecting == 0 && closingSockets == 0 && workers == 0) {
			dials.clear();
			if (closeFailure == null) closing.tryComplete(); else closing.tryFail(closeFailure);
		}
	}
}
