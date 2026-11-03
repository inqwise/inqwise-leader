package com.inqwise.leader;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CancellationException;

import io.vertx.core.Completable;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

/** Shared HTTP pool and bounded exchange registry. All methods and callbacks run on the owner context. */
final class HttpRaftClient implements RaftRpcClient {
	private static final Logger LOG = LogManager.getLogger(HttpRaftClient.class);
	static final String NODE_HEADER = "X-Inqwise-Node-Id";
	@FunctionalInterface interface Encoder { Buffer encode() throws Exception; }
	@FunctionalInterface interface Decoder<T> { T decode(Buffer bytes, String peer) throws Exception; }
	private final Context context;
	private final HttpClient client;
	private final String basePath;
	private final String nodeId;
	private final RaftPeerVerifier identity;
	private final int limit;
	private final long timeoutMillis;
	private final Set<Exchange<?>> pending = new HashSet<>();
	private Promise<Void> closeResult;
	private boolean httpClosed;
	private Throwable closeFailure;
	private int workers;

	HttpRaftClient(Context context, HttpClientOptions options, String basePath, String nodeId, RaftPeerVerifier identity,
		int limit, long timeoutMillis) {
		this.context = context; this.basePath = basePath; this.nodeId = nodeId; this.identity = identity;
		this.limit = limit; this.timeoutMillis = timeoutMillis;
		client = context.owner().createHttpClient(options);
	}

	public RaftConnection connection(NodeEndpoint endpoint) { return new HttpRaftConnection(this, endpoint); }

	<T> void request(NodeEndpoint endpoint, HttpMethod method, String suffix, Encoder encoder, Decoder<T> decoder,
		boolean large, Completable<T> completion) {
		Objects.requireNonNull(completion, "completion");
		if (closeResult != null || pending.size() >= limit) {
			completion.fail(closeResult != null ? new CancellationException("Transport closed")
				: new RaftUnavailableException("Transport request limit reached")); return;
		}
		var exchange = new Exchange<T>(endpoint, method, suffix, decoder, completion);
		pending.add(exchange);
		exchange.timer = context.owner().setTimer(timeoutMillis,
			ignored -> exchange.finish(null, new TimeoutException("HTTP dispatch deadline exceeded")));
		if (large) {
			exchange.working = true; workers++;
			context.executeBlocking(encoder::encode, false).onComplete(encoded -> {
				exchange.workerFinished();
				if (encoded.failed()) exchange.finish(null, encoded.cause()); else exchange.send(encoded.result());
				completeClose();
			});
		} else {
			try { exchange.send(encoder.encode()); } catch (Exception e) { exchange.finish(null, e); }
		}
	}

	public Future<Void> close() {
		if (closeResult == null) {
			// Mark closed before callbacks can submit new work.
			closeResult = ContextDispatch.promise(context);
			for (var exchange : List.copyOf(pending)) {
				exchange.finish(null, new CancellationException("Transport closed"));
			}
			client.close().onComplete(result -> {
				httpClosed = true; closeFailure = result.cause(); completeClose();
			});
		}
		return closeResult.future();
	}

	private void completeClose() {
		if (closeResult != null && httpClosed && workers == 0) {
			if (closeFailure == null) closeResult.tryComplete(); else closeResult.tryFail(closeFailure);
		}
	}

	private final class Exchange<T> {
		private final NodeEndpoint endpoint;
		private final HttpMethod method;
		private final String suffix;
		private Decoder<T> decoder;
		private Completable<T> completion;
		private HttpClientRequest request;
		private HttpClientResponse response;
		private boolean responseEnded;
		private boolean working;
		private Buffer bytes;
		private String peer;
		private long timer;

		Exchange(NodeEndpoint endpoint, HttpMethod method, String suffix, Decoder<T> decoder, Completable<T> completion) {
			this.endpoint = endpoint; this.method = method; this.suffix = suffix;
			this.decoder = decoder; this.completion = completion;
		}

		void send(Buffer body) {
			if (completion == null) return;
			var options = new RequestOptions().setMethod(method).setHost(endpoint.host()).setPort(endpoint.port())
				.setURI(basePath + suffix).setFollowRedirects(false).setTimeout(timeoutMillis);
			client.request(options).onComplete(created -> {
				if (created.failed()) { finish(null, created.cause()); return; }
				if (completion == null) { created.result().reset(); return; }
				request = created.result();
				request.putHeader(NODE_HEADER, nodeId).putHeader("Content-Type", RaftWireCodec.CONTENT_TYPE)
					.send(body).onComplete(received -> {
						if (received.failed()) finish(null, received.cause()); else receive(received.result());
					});
			});
		}

		void receive(HttpClientResponse received) {
			if (completion == null) return;
			response = received;
			response.exceptionHandler(failure -> { responseEnded = true; finish(null, failure); });
			try {
				peer = identity.verify(request.connection(), response.getHeader(NODE_HEADER));
				if (!peer.equals(response.getHeader(NODE_HEADER))
					|| endpoint.expectedNodeId() != null && !peer.equals(endpoint.expectedNodeId())) {
					throw new IOException("Peer identity mismatch");
				}
				boolean resultBody = method == HttpMethod.GET || suffix.equals("/join") || suffix.equals("/leave");
				int status = response.statusCode();
				if (status != (resultBody ? 200 : 202)) {
					if (status == 425 || status == 429 || status == 503) {
						throw new RaftUnavailableException("Peer HTTP status " + status);
					}
					throw new IOException("Peer HTTP status " + status);
				}
				if (resultBody && !RaftWireCodec.CONTENT_TYPE.equals(response.getHeader("Content-Type"))) {
					throw new IOException("Unsupported response content type");
				}
				bytes = Buffer.buffer();
				response.handler(chunk -> {
					if (completion == null) return;
					if (chunk.length() > RaftWireCodec.MAX_BYTES - bytes.length()) {
						finish(null, new IOException("Protocol body too large"));
					} else bytes.appendBuffer(chunk);
				}).endHandler(ignored -> { responseEnded = true; decode(); });
				response.resume();
			} catch (Exception e) { finish(null, e); }
		}

		void decode() {
			if (completion == null) return;
			Buffer body = bytes; bytes = null;
			Decoder<T> reader = decoder; decoder = null;
			String authenticated = peer;
			if (body.length() > 16384) {
				working = true; workers++;
				context.executeBlocking(() -> reader.decode(body, authenticated), false).onComplete(result -> {
					workerFinished(); finish(result.result(), result.cause()); completeClose();
				});
			} else {
				try { finish(reader.decode(body, authenticated), null); } catch (Exception e) { finish(null, e); }
			}
		}

		void workerFinished() {
			working = false; workers--;
			if (completion == null) pending.remove(this);
		}

		void finish(T value, Throwable failure) {
			if (completion == null) return;
			Completable<T> callback = completion; completion = null;
			context.owner().cancelTimer(timer);
			if (!working) pending.remove(this);
			bytes = null; decoder = null; peer = null;
			if (response != null) {
				response.handler(null).endHandler(null);
				response.exceptionHandler(responseEnded ? null : ignored -> {});
				response = null;
			}
			HttpClientRequest active = request; request = null;
			if (failure != null && active != null) active.reset();
			try { callback.complete(value, failure); }
			catch (Exception e) { LOG.warn("Raft connection completion handler failed", e); }
		}
	}
}
