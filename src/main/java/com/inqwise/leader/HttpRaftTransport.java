package com.inqwise.leader;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerOptions;

/**
 * Context-owned HTTP/1.1 Raft transport with plaintext or verified mutual TLS identity binding.
 * Routes are installed only after an identity handshake, authenticated when TLS is enabled. The engine owns retries.
 */
public final class HttpRaftTransport extends AbstractRaftClusterTransport {
	private final HttpServerOptions serverOptions;
	private final HttpClientOptions clientOptions;
	private final RaftPeerVerifier verifier;
	private final String basePath;

	/**
	 * Creates a plaintext or mutual TLS transport, using {@link RaftTlsIdentity} for TLS certificate identities.
	 * @param context store's owning event-loop context
	 * @param identity recovered persisted identity
	 * @param clusterId agreed cluster identity
	 * @param serverOptions listener options
	 * @param clientOptions client options, using the same TLS mode as the listener
	 */
	public HttpRaftTransport(Context context, RaftIdentity identity, String clusterId,
		HttpServerOptions serverOptions, HttpClientOptions clientOptions) {
		this(context, identity, clusterId, serverOptions, clientOptions, RaftTlsIdentity::nodeId);
	}

	/**
	 * Creates an unopened transport. Configure listener host/port (default 8080) through Vert.x options.
	 * Plaintext accepts self-reported peer IDs. When enabled, both TLS sides must have key/certificate and trust
	 * options; insecure TLS settings and mixed modes are rejected.
	 * @param context owner context, normally the store's context
	 * @param identity recovered persisted local identity
	 * @param clusterId agreed cluster identity
	 * @param serverOptions listener configuration, with verified mutual TLS when SSL is enabled
	 * @param clientOptions client configuration; in TLS mode, trust-all and disabled host verification are rejected
	 * @param certificateIdentity maps an authenticated leaf certificate to an authorized persisted node ID;
	 * implementations must reject certificates outside their identity policy
	 */
	public HttpRaftTransport(Context context, RaftIdentity identity, String clusterId,
		HttpServerOptions serverOptions, HttpClientOptions clientOptions,
		Function<X509Certificate, String> certificateIdentity) {
		this(context, identity, clusterId, serverOptions, clientOptions, certificateIdentity, null);
	}

	private HttpRaftTransport(Context context, RaftIdentity identity, String clusterId,
		HttpServerOptions serverOptions, HttpClientOptions clientOptions,
		Function<X509Certificate, String> certificateIdentity, RaftBootstrap bootstrap) {
		super(context, identity, clusterId, bootstrap, Objects.requireNonNull(serverOptions, "serverOptions").getHost());
		this.serverOptions = new HttpServerOptions(Objects.requireNonNull(serverOptions, "serverOptions"));
		this.clientOptions = new HttpClientOptions(Objects.requireNonNull(clientOptions, "clientOptions"));
		if (serverOptions.isSsl() != clientOptions.isSsl()) {
			throw new IllegalArgumentException("Server and client must use the same TLS mode");
		}
		if (serverOptions.isSsl() && (serverOptions.getClientAuth() != ClientAuth.REQUIRED
			|| serverOptions.getKeyCertOptions() == null || serverOptions.getTrustOptions() == null
			|| clientOptions.isTrustAll() || !clientOptions.isVerifyHost()
			|| clientOptions.getKeyCertOptions() == null || clientOptions.getTrustOptions() == null)) {
			throw new IllegalArgumentException("Verified mutual TLS is required on both server and client");
		}
		var certificates = Objects.requireNonNull(certificateIdentity, "certificateIdentity");
		this.verifier = connection -> {
			if (this.serverOptions.isSsl()) return RaftTlsIdentity.verify(connection, identity.nodeId(), certificates);
			if (connection.isSsl()) throw new java.io.IOException("Unexpected TLS connection in plaintext mode");
			return null;
		};
		this.basePath = "/leader/v1/groups/" + URLEncoder.encode(identity.groupName(), StandardCharsets.UTF_8)
			.replace("+", "%20");
		// Keep transport resource bounds independent of caller-provided HTTP defaults.
		this.clientOptions.setProtocolVersion(io.vertx.core.http.HttpVersion.HTTP_1_1).setPipelining(false)
			.setConnectTimeout((int) TIMEOUT_MILLIS).setDecompressionSupported(false).setShared(false)
			.setSslHandshakeTimeout(5).setSslHandshakeTimeoutUnit(TimeUnit.SECONDS);
		this.serverOptions.setIdleTimeout(5).setIdleTimeoutUnit(TimeUnit.SECONDS)
			.setMaxHeaderSize(8192).setMaxInitialLineLength(16384).setDecompressionSupported(false)
			.setSslHandshakeTimeout(5).setSslHandshakeTimeoutUnit(TimeUnit.SECONDS);
	}

	/**
	 * Creates a transport that reports local durable assignment and exposes bootstrap endpoints.
	 * @param bootstrap designated-authority controller using the local store
	 * @param serverOptions listener configuration
	 * @param clientOptions matching client configuration, with verified mutual TLS when SSL is enabled
	 * @return unopened transport recovered from local durable state
	 */
	public static Future<HttpRaftTransport> forBootstrap(RaftBootstrap bootstrap,
		HttpServerOptions serverOptions, HttpClientOptions clientOptions) {
		return ContextDispatch.call(bootstrap.context(), () -> bootstrap.open()
			.compose(v -> bootstrap.store().identity()).compose(identity -> bootstrap.state().map(binding -> {
				var transport = new HttpRaftTransport(bootstrap.context(), identity,
					binding.genesis() == null ? null : binding.genesis().clusterId(), serverOptions, clientOptions,
					RaftTlsIdentity::nodeId, bootstrap);
				transport.installed(binding.installed());
				return transport;
			})));
	}

	@Override RaftRpcClient createClient() {
		return new HttpRaftClient(context, clientOptions, basePath, identity.nodeId(), verifier,
			MAX_IN_FLIGHT, TIMEOUT_MILLIS);
	}

	@Override RaftRpcServer createServer() {
		return new HttpRaftServer(context, serverOptions, basePath, identity.nodeId(), verifier,
			MAX_IN_FLIGHT, TIMEOUT_MILLIS);
	}
}
