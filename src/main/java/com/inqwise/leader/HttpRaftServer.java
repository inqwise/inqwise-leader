package com.inqwise.leader;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;

/** Owner-context HTTP framing and exchange lifecycle; typed handlers own protocol and membership policy. */
final class HttpRaftServer implements Handler<HttpServerRequest> {
	@FunctionalInterface interface Decoder<T> { T decode(Buffer bytes) throws Exception; }
	@FunctionalInterface interface Encoder<T> { Buffer encode(T value) throws Exception; }
	@FunctionalInterface interface RequestHandler<T, R> {
		void handle(String peer, T message, RaftRequestCompletion<R> completion);
	}
	private record Route<T, R>(Decoder<T> decoder, Encoder<R> encoder, RequestHandler<T, R> handler,
		ToIntFunction<String> rejection, int failureStatus) {}
	private final Context context;
	private final HttpServer server;
	private final String basePath;
	private final String nodeId;
	private final RaftPeerVerifier verifier;
	private final int limit;
	private final long timeoutMillis;
	private final Map<String, Route<?, ?>> gets = new HashMap<>();
	private final Map<String, Route<?, ?>> posts = new HashMap<>();
	private final Set<Incoming<?, ?>> pending = new HashSet<>();
	private Future<Integer> listening;
	private Future<Void> closing;
	private Promise<Void> closeResult;
	private boolean httpClosed;
	private Throwable closeFailure;
	private int workers;

	HttpRaftServer(Context context, HttpServerOptions options, String basePath, String nodeId,
		RaftPeerVerifier verifier, int limit, long timeoutMillis) {
		this.context = context; this.basePath = basePath; this.nodeId = nodeId;
		this.verifier = verifier; this.limit = limit; this.timeoutMillis = timeoutMillis;
		server = context.owner().createHttpServer(options).requestHandler(this);
	}

	<R> void get(String suffix, Encoder<R> encoder, RequestHandler<Void, R> handler,
		ToIntFunction<String> rejection, int failureStatus) {
		register(gets, suffix, new Route<>(null, encoder, handler, rejection, failureStatus));
	}

	<T, R> void post(String suffix, Decoder<T> decoder, Encoder<R> encoder, RequestHandler<T, R> handler,
		ToIntFunction<String> rejection, int failureStatus) {
		register(posts, suffix, new Route<>(decoder, encoder, handler, rejection, failureStatus));
	}

	private void register(Map<String, Route<?, ?>> routes, String suffix, Route<?, ?> route) {
		if (listening != null || closing != null) throw new IllegalStateException("Register handlers before listen");
		if (routes.putIfAbsent(basePath + suffix, route) != null) throw new IllegalArgumentException("Duplicate route");
	}

	Future<Integer> listen() {
		if (closing != null) return Future.failedFuture("Listener closed");
		if (listening == null) listening = server.listen().map(HttpServer::actualPort);
		return listening;
	}
	int actualPort() { return server.actualPort(); }

	Future<Void> close() {
		if (closing == null) {
			closeResult = ContextDispatch.promise(context); closing = closeResult.future();
			for (var request : List.copyOf(pending)) request.finish(503, null);
			var stopped = listening == null ? server.close() : listening.transform(ignored -> server.close());
			stopped.onComplete(result -> { httpClosed = true; closeFailure = result.cause(); completeClose(); });
		}
		return closing;
	}

	private void completeClose() {
		if (closeResult != null && httpClosed && workers == 0) {
			if (closeFailure == null) closeResult.tryComplete(); else closeResult.tryFail(closeFailure);
		}
	}

	@Override public void handle(HttpServerRequest request) {
		request.pause();
		if (Vertx.currentContext() != context) { context.runOnContext(ignored -> handle(request)); return; }
		request.response().putHeader(HttpRaftClient.NODE_HEADER, nodeId);
		if (closing != null) { reject(request, 503); return; }
		if (pending.size() >= limit) { reject(request, 429); return; }
		try {
			String peer = verifier.verify(request.connection(), request.getHeader(HttpRaftClient.NODE_HEADER));
			var routes = request.method() == HttpMethod.GET ? gets : request.method() == HttpMethod.POST ? posts : null;
			var route = routes == null ? null : routes.get(request.uri());
			if (route == null) { reject(request, 404); return; }
			int rejected = route.rejection().applyAsInt(peer);
			if (rejected != 0) { reject(request, rejected); return; }
			if (route.decoder() == null) {
				if (request.getHeader("Transfer-Encoding") != null || request.getHeader("Content-Length") != null
					&& !request.getHeader("Content-Length").equals("0")) { reject(request, 400); return; }
			} else if (!RaftWireCodec.CONTENT_TYPE.equals(request.getHeader("Content-Type"))) {
				reject(request, 415); return;
			}
			new Incoming<>(request, peer, route).start();
		} catch (Exception e) { reject(request, 403); }
	}

	private void onOwner(Runnable action) {
		if (Vertx.currentContext() == context) action.run(); else context.runOnContext(ignored -> action.run());
	}

	private static void reject(HttpServerRequest request, int status) {
		if (!request.response().ended()) request.response().setStatusCode(status).putHeader("Connection", "close")
			.end().onComplete(ignored -> request.connection().close());
	}

	private final class Incoming<T, R> implements RaftRequestCompletion<R> {
		private HttpServerRequest request;
		private final String peer;
		private final Route<T, R> route;
		private Buffer bytes;
		private boolean active = true;
		private boolean working;
		private boolean processing;
		private long timer;

		Incoming(HttpServerRequest request, String peer, Route<T, R> route) {
			this.request = request; this.peer = peer; this.route = route;
		}

		@Override public boolean isActive() { return active; }

		void start() {
			pending.add(this);
			timer = context.owner().setTimer(timeoutMillis, ignored -> finish(route.failureStatus(), null));
			request.exceptionHandler(ignored -> onOwner(() -> finish(route.failureStatus(), null)));
			request.response().closeHandler(ignored -> onOwner(() -> finish(route.failureStatus(), null)));
			if (route.decoder() == null) {
				request.resume(); dispatch(null); return;
			}
			bytes = Buffer.buffer();
			HttpServerRequest stream = request;
			stream.handler(chunk -> {
				stream.pause();
				onOwner(() -> {
					if (!active) return;
					if (chunk.length() > RaftWireCodec.MAX_BYTES - bytes.length()) finish(413, null);
					else { bytes.appendBuffer(chunk); stream.resume(); }
				});
			}).endHandler(ignored -> onOwner(this::decode));
			request.resume();
		}

		void decode() {
			if (!active) return;
			Buffer body = bytes; bytes = null;
			if (body.length() > 16384) {
				working = true; workers++;
				context.executeBlocking(() -> route.decoder().decode(body), false).onComplete(result -> {
					working = false; workers--;
					if (active) {
						if (result.failed()) finish(route.failureStatus(), null); else dispatch(result.result());
					}
					release(); completeClose();
				});
			} else {
				try { dispatch(route.decoder().decode(body)); } catch (Exception e) { finish(route.failureStatus(), null); }
			}
		}

		void dispatch(T value) {
			if (!active) return;
			processing = true;
			try { route.handler().handle(peer, value, this); } catch (Exception e) { complete(null, e); }
		}

		@Override public void complete(R value, Throwable failure) {
			if (Vertx.currentContext() != context) { context.runOnContext(ignored -> complete(value, failure)); return; }
			if (!processing) return;
			processing = false;
			if (active) {
				try {
					if (failure != null) finish(route.failureStatus(), null);
					else finish(route.encoder() == null ? 202 : 200, route.encoder() == null ? null : route.encoder().encode(value));
				} catch (Exception e) { finish(route.failureStatus(), null); }
			}
			release();
		}

		void finish(int status, Buffer body) {
			if (!active) { release(); return; }
			active = false; context.owner().cancelTimer(timer); bytes = null;
			HttpServerRequest current = request; request = null;
			current.handler(null).endHandler(null).exceptionHandler(null);
			current.response().closeHandler(null);
			release();
			if (status >= 400) reject(current, status);
			else if (!current.response().ended()) {
				if (body == null) current.response().setStatusCode(status).end();
				else current.response().putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE).end(body);
			}
		}

		void release() {
			if (!active && !working && (!processing || closing != null)) pending.remove(this);
		}
	}
}
