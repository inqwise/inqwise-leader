package com.inqwise.leader;

import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServerOptions;

/**
 * Persistent, framed TCP Raft communication, independent of Vert.x clustering.
 * TLS requires mutual authentication and HTTPS hostname verification. Plaintext identities are self-reported.
 * The engine owns retries; failed connection/handshake attempts use bounded backoff. Bulk traffic uses a separate peer connection.
 */
public final class TcpRaftTransport extends AbstractRaftClusterTransport {
	private final NetServerOptions serverOptions;
	private final NetClientOptions clientOptions;
	private final Function<X509Certificate, String> certificateIdentity;
	private TcpRaftRpc rpc;

	/**
	 * Creates an unopened transport with the standard node URI SAN identity convention.
	 * @param context owner event-loop context
	 * @param identity durable local identity
	 * @param clusterId installed cluster ID
	 * @param serverOptions listener and TLS options
	 * @param clientOptions client and matching TLS options
	 */
	public TcpRaftTransport(Context context, RaftIdentity identity, String clusterId,
		NetServerOptions serverOptions, NetClientOptions clientOptions) {
		this(context, identity, clusterId, serverOptions, clientOptions, RaftTlsIdentity::nodeId);
	}

	/**
	 * Creates an unopened transport with a custom authorized certificate-to-node mapping.
	 * @param context owner event-loop context
	 * @param identity durable local identity
	 * @param clusterId installed cluster ID
	 * @param serverOptions listener and TLS options
	 * @param clientOptions client and matching TLS options
	 * @param certificateIdentity authorized certificate identity policy
	 */
	public TcpRaftTransport(Context context, RaftIdentity identity, String clusterId,
		NetServerOptions serverOptions, NetClientOptions clientOptions,
		Function<X509Certificate, String> certificateIdentity) {
		this(context, identity, clusterId, serverOptions, clientOptions, certificateIdentity, null);
	}

	private TcpRaftTransport(Context context, RaftIdentity identity, String clusterId,
		NetServerOptions serverOptions, NetClientOptions clientOptions,
		Function<X509Certificate, String> certificateIdentity, RaftBootstrap bootstrap) {
		super(context, identity, clusterId, bootstrap, Objects.requireNonNull(serverOptions, "serverOptions").getHost());
		this.serverOptions = new NetServerOptions(serverOptions);
		this.clientOptions = new NetClientOptions(Objects.requireNonNull(clientOptions, "clientOptions"));
		this.certificateIdentity = Objects.requireNonNull(certificateIdentity, "certificateIdentity");
		if (serverOptions.isSsl() != clientOptions.isSsl()) {
			throw new IllegalArgumentException("Server and client must use the same TLS mode");
		}
		if (serverOptions.isSsl() && (serverOptions.getClientAuth() != ClientAuth.REQUIRED
			|| serverOptions.getKeyCertOptions() == null || serverOptions.getTrustOptions() == null
			|| clientOptions.isTrustAll() || !"HTTPS".equals(clientOptions.getHostnameVerificationAlgorithm())
			|| clientOptions.getKeyCertOptions() == null || clientOptions.getTrustOptions() == null)) {
			throw new IllegalArgumentException("Verified mutual TLS and HTTPS hostname verification are required");
		}
		this.serverOptions.setTcpNoDelay(true).setTcpKeepAlive(true)
			.setSslHandshakeTimeout(5).setSslHandshakeTimeoutUnit(TimeUnit.SECONDS);
		this.clientOptions.setTcpNoDelay(true).setTcpKeepAlive(true).setReconnectAttempts(0)
			.setConnectTimeout((int) TIMEOUT_MILLIS)
			.setSslHandshakeTimeout(5).setSslHandshakeTimeoutUnit(TimeUnit.SECONDS);
	}

	/**
	 * Recovers local durable assignment and enables designated-authority bootstrap.
	 * @param bootstrap bootstrap controller using the local store
	 * @param serverOptions listener and TLS options
	 * @param clientOptions matching client options
	 * @return unopened recovered transport
	 */
	public static Future<TcpRaftTransport> forBootstrap(RaftBootstrap bootstrap,
		NetServerOptions serverOptions, NetClientOptions clientOptions) {
		return ContextDispatch.call(bootstrap.context(), () -> bootstrap.open()
			.compose(v -> bootstrap.store().identity()).compose(identity -> bootstrap.state().map(binding -> {
				var transport = new TcpRaftTransport(bootstrap.context(), identity,
					binding.genesis() == null ? null : binding.genesis().clusterId(), serverOptions, clientOptions,
					RaftTlsIdentity::nodeId, bootstrap);
				transport.installed(binding.installed());
				return transport;
			})));
	}

	@Override RaftRpcClient createClient() {
		rpc = new TcpRaftRpc(context, identity, serverOptions, clientOptions, certificateIdentity,
			MAX_IN_FLIGHT, TIMEOUT_MILLIS);
		return rpc;
	}

	@Override RaftRpcServer createServer() { return rpc; }
}
