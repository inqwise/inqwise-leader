package com.inqwise.leader;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.BiConsumer;

import java.util.concurrent.CancellationException;

import io.vertx.core.Context;
import io.vertx.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerOptions;

/**
 * Context-owned HTTP/1.1 Raft transport with plaintext or verified mutual TLS identity binding.
 * Routes are installed only after an identity handshake, authenticated when TLS is enabled. The engine owns retries.
 */
public final class HttpRaftTransport implements RaftClusterTransport {
	private static final int MAX_IN_FLIGHT = 64;
	private static final long TIMEOUT_MILLIS = 5000;
	private final Context context;
	private final RaftIdentity identity;
	private String clusterId;
	private final RaftBootstrap bootstrap;
	private boolean installed = true;
	private final HttpServerOptions serverOptions;
	private final HttpClientOptions clientOptions;
	private final RaftPeerVerifier verifier;
	private final String basePath;
	private final RaftPeerRegistry peers;
	private HttpRaftServer server;
	private HttpRaftClient client;
	private Function<RaftElection.Envelope, Future<Void>> receiver;
	private Future<Integer> listenFuture;
	private Future<Void> closeFuture;
	private boolean closed;
	private VertxLeaderNode attachedNode;
	private Function<JoinRequest, Future<MemberStatus>> admissionHandler;
	private Function<LeaveRequest, Future<LeaveResult>> leaveHandler;
	private boolean departing;

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
		this.context = Objects.requireNonNull(context, "context");
		if (!context.isEventLoopContext()) throw new IllegalArgumentException("An event-loop context is required");
		this.identity = Objects.requireNonNull(identity, "identity");
		this.bootstrap = bootstrap;
		this.clusterId = clusterId;
		if ((bootstrap == null && clusterId == null) || (clusterId != null && clusterId.isBlank())) {
			throw new IllegalArgumentException("A cluster ID is required");
		}
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
		this.peers = new RaftPeerRegistry(identity.nodeId(), MAX_IN_FLIGHT);
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
				transport.installed = binding.installed();
				return transport;
			})));
	}

	/**
	 * Forms once on the designated authority and retries installation of the exact durable record.
	 * @param endpoints eligible static or SRV endpoints
	 * @return immutable record plus per-voter installation failures; failures never select replacement voters
	 */
	public Future<BootstrapResult> bootstrap(List<NodeEndpoint> endpoints) {
		return ContextDispatch.call(context, () -> {
			ensureListening();
			if (bootstrap == null || !identity.nodeId().equals(bootstrap.authorityId())) {
				throw new IllegalStateException("This transport is not the bootstrap authority");
			}
			return discover(endpoints).compose(discovery -> bootstrap.form(discovery.peers()))
				.compose(genesis -> refreshBinding().compose(v -> distribute(genesis)));
		});
	}

	/**
	 * Fetches the immutable record from the configured authenticated authority and installs it locally.
	 * @param authority authority endpoint; its expected identity must match the configured authority
	 * @return exact genesis after local durable installation
	 */
	public Future<RaftGenesis> fetchBootstrap(NodeEndpoint authority) {
		return ContextDispatch.call(context, () -> {
			ensureListening();
			if (bootstrap == null) throw new IllegalStateException("Bootstrap is not enabled");
			if (authority.expectedNodeId() != null && !authority.expectedNodeId().equals(bootstrap.authorityId())) {
				throw new IllegalArgumentException("Unexpected authority identity");
			}
			var expected = new NodeEndpoint(authority.host(), authority.port(), bootstrap.authorityId());
			return request(expected, RaftConnection::genesis)
				.compose(genesis -> {
					ensureActive();
					return bootstrap.install(genesis, bootstrap.authorityId()).compose(v -> refreshBinding()).map(genesis);
				});
		});
	}

	private Future<BootstrapResult> distribute(RaftGenesis genesis) {
		ensureActive();
		var installations = new HashMap<String, Future<Void>>();
		for (String voter : genesis.voters()) {
			if (voter.equals(identity.nodeId())) {
				installations.put(voter, bootstrap.install(genesis, identity.nodeId()).compose(v -> refreshBinding()));
			} else {
				var endpoint = peers.endpoint(voter);
				installations.put(voter, endpoint == null ? Future.failedFuture(new RaftUnavailableException("Selected voter is unavailable"))
					: request(endpoint, (connection, completion) -> connection.install(genesis, completion)));
			}
		}
		return Future.join(List.copyOf(installations.values())).transform(ignored -> {
			var failures = new HashMap<String, String>();
			installations.forEach((voter, result) -> {
				if (result.failed()) failures.put(voter, String.valueOf(result.cause().getMessage()));
			});
			return Future.succeededFuture(new BootstrapResult(genesis, failures));
		});
	}

	private Future<Void> refreshBinding() {
		if (bootstrap == null || (installed && clusterId != null)) return Future.succeededFuture();
		return bootstrap.state().map(binding -> {
			clusterId = binding.genesis() == null ? null : binding.genesis().clusterId();
			installed = binding.installed();
			return (Void) null;
		});
	}

	/**
	 * Binds the listener and enables dispatch. Use {@code node::receive} as receiver.
	 * HTTP acceptance confirms dispatch only; Raft responses travel as separate protocol requests after durability.
	 * @param receiver asynchronous protocol receiver
	 * @return actual listening port, including an ephemeral port when configured as zero
	 */
	public Future<Integer> listen(Function<RaftElection.Envelope, Future<Void>> receiver) {
		return ContextDispatch.call(context, () -> {
			ensureActive();
			if (listenFuture != null) return listenFuture;
			this.receiver = Objects.requireNonNull(receiver, "receiver");
			client = new HttpRaftClient(context, clientOptions, basePath, identity.nodeId(), verifier,
				MAX_IN_FLIGHT, TIMEOUT_MILLIS);
			server = new HttpRaftServer(context, serverOptions, basePath, identity.nodeId(),
				verifier, MAX_IN_FLIGHT, TIMEOUT_MILLIS);
			registerRequests();
			listenFuture = server.listen().compose(port -> closed
				? Future.failedFuture(new CancellationException("Transport closed during bind"))
				: Future.succeededFuture(port));
			return listenFuture;
		});
	}

	/**
	 * Opens a listener before the participant is assigned or recovered.
	 * @return bound port
	 */
	public Future<Integer> listen() {
		return listen(envelope -> attachedNode == null ? Future.failedFuture(new RaftUnavailableException("Participant not ready"))
			: attachedNode.receive(envelope));
	}

	/**
	 * Identifies the transport owner context.
	 * @return owning event-loop context
	 */
	public Context context() { return context; }

	/**
	 * Attaches the local participant and its membership status endpoint.
	 * @param node local participant on this transport's context
	 * @return attachment completion
	 */
	public Future<Void> attachNode(VertxLeaderNode node) {
		return ContextDispatch.call(context, () -> {
			ensureActive();
			if (node.context() != context || (attachedNode != null && attachedNode != node)) {
				throw new IllegalArgumentException("A different participant/context is already attached");
			}
			attachedNode = node; receiver = node::receive;
			return Future.succeededFuture();
		});
	}

	/**
	 * Registers the controller's serialized leader-only admission policy.
	 * @param handler admission operation, or null to disable
	 * @return registration completion
	 */
	public Future<Void> admissionHandler(Function<JoinRequest, Future<MemberStatus>> handler) {
		return ContextDispatch.call(context, () -> { ensureActive(); admissionHandler = handler; return Future.succeededFuture(); });
	}

	/**
	 * Reads concrete local listener address; wildcard listeners require an explicit advertised endpoint.
	 * @return local IPv4 endpoint
	 */
	public Future<NodeEndpoint> localEndpoint() {
		return ContextDispatch.call(context, () -> {
			ensureListening(); return Future.succeededFuture(new NodeEndpoint(serverOptions.getHost(), server.actualPort(), identity.nodeId()));
		});
	}

	/**
	 * Reads the immutable authority configuration.
	 * @return explicitly designated authority identity, or null for manual transport mode
	 */
	public String bootstrapAuthorityId() { return bootstrap == null ? null : bootstrap.authorityId(); }

	/**
	 * Retains verified endpoints across discovery failures for member health probing.
	 * @return immutable route snapshot
	 */
	public Future<List<NodeEndpoint>> knownEndpoints() {
		return ContextDispatch.call(context, () -> { ensureActive(); return Future.succeededFuture(peers.endpoints()); });
	}

	/**
	 * Removes aged nonmember routes while retaining configured members and recent admission callbacks.
	 * @param members current configuration members
	 * @param ageMillis minimum time since successful verification
	 * @return cleanup completion
	 */
	public Future<Void> pruneRoutes(Set<String> members, long ageMillis) {
		var keep = Set.copyOf(members);
		return ContextDispatch.call(context, () -> {
			ensureActive();
			if (ageMillis < 1) throw new IllegalArgumentException("Positive route age is required");
			peers.prune(keep, ageMillis);
			return Future.succeededFuture();
		});
	}

	/**
	 * Verifies one callback route without superseding a concurrent discovery generation.
	 * @param endpoint concrete expected peer
	 * @return authenticated peer
	 */
	public Future<DiscoveredPeer> verifyPeer(NodeEndpoint endpoint) {
		return ContextDispatch.call(context, () -> {
			ensureListening();
			Promise<DiscoveredPeer> result = ContextDispatch.promise(context);
			identify(endpoint, (peer, failure) -> {
				if (failure != null) { result.tryFail(failure); return; }
				try { ensureActive(); peers.remember(peer); result.tryComplete(peer); }
				catch (Exception e) { result.tryFail(e); }
			});
			return result.future();
		});
	}

	/**
	 * Reads an authenticated peer's coherent membership/leadership observation.
	 * @param endpoint expected peer
	 * @return peer status
	 */
	public Future<MemberStatus> memberStatus(NodeEndpoint endpoint) {
		return ContextDispatch.call(context, () -> {
			ensureListening();
			return request(endpoint, RaftConnection::membership);
		});
	}

	/**
	 * Asks a freshly observed ready leader to commit learner admission, then installs genesis locally.
	 * @param leader expected leader endpoint
	 * @param advertised local concrete callback endpoint
	 * @return installed immutable genesis; voting rights still require later catch-up and joint consensus
	 */
	public Future<RaftGenesis> joinCluster(NodeEndpoint leader, NodeEndpoint advertised) {
		return ContextDispatch.call(context, () -> {
			ensureListening();
			if (bootstrap == null || departing) return Future.failedFuture(new RaftUnavailableException("Admission is unavailable while departing or unbound"));
			return request(leader, RaftConnection::membership).compose(status -> {
				if (!status.ready() || !identity.groupName().equals(status.genesis().groupName())
					|| !bootstrap.authorityId().equals(status.genesis().authorityId())
					|| (clusterId == null && status.genesis().voters().contains(identity.nodeId()))) {
					return Future.failedFuture(new RaftUnavailableException("Ready leader admission is required; initial voters must recover/bootstrap"));
				}
				return refreshBinding().compose(v -> {
					if (clusterId != null && !clusterId.equals(status.genesis().clusterId())) throw new SecurityException("Cluster mismatch");
					var request = new JoinRequest(identity, clusterId, advertised, status.term());
					var expected = new NodeEndpoint(leader.host(), leader.port(), status.identity().nodeId());
					return this.<MemberStatus>request(expected, (connection, completion) -> connection.join(request, completion))
						.compose(admitted -> {
							ensureActive();
							if (departing) return Future.failedFuture(new CancellationException("Node is departing"));
							if (!admitted.ready() || admitted.term() != status.term() || !admitted.genesis().equals(status.genesis())
								|| !admitted.configuration().learners().contains(identity.nodeId())) return Future.failedFuture(new RaftUnavailableException("Admission changed during request"));
							Future<Void> installation = admitted.genesis().voters().contains(identity.nodeId())
								? bootstrap.store().installGenesis(admitted.genesis()) : bootstrap.store().installLearnerGenesis(admitted.genesis());
							return installation.compose(vv -> refreshBinding()).map(admitted.genesis());
						});
				});
			});
		});
	}

	/**
	 * Registers authenticated self-removal handling on the owning context.
	 * @param handler leader-side removal operation, or null to disable
	 * @return registration completion
	 */
	public Future<Void> leaveHandler(Function<LeaveRequest, Future<LeaveResult>> handler) {
		return ContextDispatch.call(context, () -> { ensureActive(); leaveHandler = handler; return Future.succeededFuture(); });
	}

	/**
	 * Suppresses local admission attempts before departure.
	 * @return completion after suppressing new admissions for local departure
	 */
	@Override public Future<Void> beginLeave() {
		return ContextDispatch.call(context, () -> { ensureActive(); departing = true; return Future.succeededFuture(); });
	}

	/**
	 * Requests this identity's committed removal from a freshly observed ready leader.
	 * Suppresses future admission requests on this transport; the caller owns local drain/shutdown and retries.
	 * @param leader authenticated leader endpoint
	 * @return committed removal receipt; a failure or timeout leaves the outcome unknown
	 */
	public Future<LeaveResult> leaveCluster(NodeEndpoint leader) {
		return ContextDispatch.call(context, () -> {
			ensureListening(); departing = true;
			return refreshBinding().compose(v -> {
				if (!installed || clusterId == null) return Future.failedFuture(new RaftUnavailableException("Installed cluster binding is required"));
				return request(leader, RaftConnection::membership);
			}).compose(status -> {
				if (!status.ready() || !clusterId.equals(status.genesis().clusterId())
					|| !identity.groupName().equals(status.identity().groupName())) return Future.failedFuture(new RaftUnavailableException("Ready bound leader required"));
				var expected = new NodeEndpoint(leader.host(), leader.port(), status.identity().nodeId());
				var request = new LeaveRequest(identity, clusterId, status.term());
				return this.<LeaveResult>request(expected, (connection, completion) -> connection.leave(request, completion))
					.map(result -> {
						ensureActive();
						if (!result.clusterId().equals(clusterId) || result.term() != status.term()
							|| !result.identity().groupName().equals(identity.groupName())
							|| !result.nodeId().equals(identity.nodeId())) throw new IllegalStateException("Removal response mismatch");
						return result;
					});
			});
		});
	}

	/**
	 * Reads coherent local membership status.
	 * @return coherent local membership observation, or failure while unavailable
	 */
	@Override public Future<MemberStatus> localMemberStatus() {
		if (bootstrap == null || attachedNode == null) return Future.failedFuture(new RaftUnavailableException("Participant not ready"));
		return bootstrap.state().compose(binding -> {
			if (!binding.installed()) return Future.failedFuture(new RaftUnavailableException("Cluster admission is pending"));
			return attachedNode.membershipView().map(view -> new MemberStatus(identity, binding.genesis(),
				view.leadership().term(), view.leadership().leaderId(), view.leadership().leader(), view.membership().effective()));
		});
	}

	/**
	 * Handshakes endpoints, installs unambiguous authenticated routes, and retains old routes on contact failure.
	 * A newer refresh supersedes an older one. Conflicting identities are quarantined by removing their routes.
	 * @param endpoints static or SRV-resolved concrete endpoints
	 * @return successful peers plus endpoint-specific failures
	 */
	public Future<DiscoveryResult> discover(List<NodeEndpoint> endpoints) {
		final List<NodeEndpoint> copy = List.copyOf(endpoints);
		return ContextDispatch.call(context, () -> {
			ensureListening();
			if (copy.size() > MAX_IN_FLIGHT) throw new IllegalArgumentException("At most 64 discovery endpoints are supported");
			Promise<DiscoveryResult> result = ContextDispatch.promise(context);
			peers.discover(copy, this::identify, result);
			return result.future();
		});
	}

	@Override
	public Future<Void> validateBinding(RaftIdentity localIdentity, RaftGenesis genesis) {
		return validateBinding(localIdentity, genesis, false);
	}

	@Override
	public Future<Void> validateBinding(RaftIdentity localIdentity, RaftGenesis genesis, boolean learnerAdmission) {
		return ContextDispatch.call(context, () -> {
			ensureActive();
			if (!identity.equals(localIdentity) || !identity.groupName().equals(genesis.groupName())
				|| (!learnerAdmission && !genesis.voters().contains(identity.nodeId()))
				|| (bootstrap == null && !Objects.equals(clusterId, genesis.clusterId()))
				|| (bootstrap != null && !bootstrap.authorityId().equals(genesis.authorityId()))) {
				throw new IllegalStateException("Transport policy conflicts with local cluster binding");
			}
			return Future.succeededFuture();
		});
	}

	/**
	 * Sends to one verified member; no redirects or load balancing are used.
	 * @param envelope protocol message from this node
	 * @return authenticated HTTP dispatch acceptance, not peer durability
	 */
	@Override public Future<Void> send(RaftElection.Envelope envelope) {
		return ContextDispatch.call(context, () -> refreshBinding().compose(v -> {
			ensureListening();
			if (!installed || clusterId == null) return Future.failedFuture(new RaftUnavailableException("Bootstrap installation is required"));
			if (!identity.nodeId().equals(envelope.from()) || !identity.groupName().equals(envelope.groupName())
				|| !clusterId.equals(envelope.clusterId())) throw new IllegalArgumentException("Foreign outbound envelope");
			NodeEndpoint endpoint = peers.endpoint(envelope.to());
			if (endpoint == null) return Future.failedFuture(new RaftUnavailableException("No verified route for " + envelope.to()));
			return this.<Void>request(endpoint, (connection, completion) -> connection.send(envelope, completion));
		}));
	}

	/**
	 * Cancels outstanding requests and closes the owned client and listener.
	 * @return resource cleanup completion
	 */
	public Future<Void> close() {
		return ContextDispatch.call(context, () -> {
			if (closeFuture == null) {
				closed = true;
				peers.close();
				Future<Void> listenerClosed = server == null ? Future.succeededFuture()
					: server.close();
				Future<Void> clientClosed = client == null ? Future.succeededFuture() : client.close();
				closeFuture = Future.join(listenerClosed, clientClosed).mapEmpty();
			}
			return closeFuture;
		});
	}

	private void identify(NodeEndpoint endpoint, Completable<DiscoveredPeer> completion) {
		client.connection(endpoint).identify((peer, failure) -> {
			if (failure != null) { completion.fail(failure); return; }
			DiscoveredPeer verified;
			try {
				if (clusterId != null && peer.clusterId() != null && !clusterId.equals(peer.clusterId())) {
					throw new RaftPeerRegistry.ClusterMismatch(peer.clusterId());
				}
				if (!identity.groupName().equals(peer.groupName())
					|| bootstrap == null && !Objects.equals(clusterId, peer.clusterId())
					|| endpoint.expectedNodeId() != null && !endpoint.expectedNodeId().equals(peer.nodeId())) {
					throw new IllegalArgumentException("Peer group, cluster, or expected identity mismatch");
				}
				verified = new DiscoveredPeer(new RaftIdentity(peer.nodeId(), peer.groupName()), peer.clusterId(),
					new NodeEndpoint(endpoint.host(), endpoint.port(), peer.nodeId()), peer.departing());
			} catch (Exception e) { completion.fail(e); return; }
			completion.succeed(verified);
		});
	}

	private <T> Future<T> request(NodeEndpoint endpoint, BiConsumer<RaftConnection, Completable<T>> operation) {
		ensureActive();
		Promise<T> result = ContextDispatch.promise(context);
		operation.accept(client.connection(endpoint), result);
		return result.future();
	}

	private void registerRequests() {
		server.get("/identity", value -> RaftWireCodec.identity(
			new RaftIdentity(value.nodeId(), value.groupName()), value.clusterId(), value.departing()),
			this::receiveIdentity, peer -> 0, 503);
		server.<MemberStatus>get("/membership", RaftWireCodec::status,
			(peer, ignored, completion) -> localMemberStatus().onComplete(completion), peer -> 0, 425);
		server.post("/join", RaftWireCodec::join, RaftWireCodec::status, this::receiveJoin,
			peer -> admissionHandler == null || attachedNode == null || peers.quarantined(peer) ? 503 : 0, 409);
		server.post("/leave", RaftWireCodec::leave, RaftWireCodec::left, this::receiveLeave,
			peer -> leaveHandler == null || attachedNode == null || peers.quarantined(peer) ? 503 : 0, 409);
		for (String suffix : List.of("/request-vote", "/vote-response", "/append-entries", "/append-response",
			"/install-snapshot", "/snapshot-response")) {
			server.post(suffix, body -> {
				var envelope = RaftWireCodec.decode(body);
				if (!suffix.equals(RaftWireCodec.suffix(envelope.message()))) throw new IOException("Protocol path mismatch");
				return envelope;
			}, null, this::receiveProtocol, peer -> peers.quarantined(peer) ? 403 : 0, 400);
		}
		if (bootstrap != null) {
			server.get("/bootstrap", RaftWireCodec::genesis, this::receiveGenesis,
				peer -> identity.nodeId().equals(bootstrap.authorityId()) ? 0 : 403, 425);
			server.post("/bootstrap/install", RaftWireCodec::genesis, null, this::receiveInstallation,
				peer -> peer.equals(bootstrap.authorityId()) ? 0 : 403, 409);
		}
	}

	private void receiveIdentity(String peer, Void ignored, RaftRequestCompletion<RaftWireCodec.PeerIdentity> completion) {
		whenBound(() -> completion.succeed(new RaftWireCodec.PeerIdentity(
			identity.nodeId(), identity.groupName(), clusterId, departing)), completion);
	}

	private void receiveProtocol(String peer, RaftElection.Envelope envelope, RaftRequestCompletion<Void> completion) {
		whenBound(() -> {
			if (!installed || clusterId == null) {
				completion.fail(new RaftUnavailableException("Bootstrap installation is required")); return;
			}
			if (!peer.equals(envelope.from()) || !identity.nodeId().equals(envelope.to())
				|| !identity.groupName().equals(envelope.groupName()) || !clusterId.equals(envelope.clusterId())) {
				completion.fail(new SecurityException("Protocol identity or cluster mismatch")); return;
			}
			receiver.apply(envelope).onComplete(completion);
		}, completion);
	}

	private void receiveJoin(String peer, JoinRequest join, RaftRequestCompletion<MemberStatus> completion) {
		if (closed) { completion.fail(new CancellationException("Transport closed")); return; }
		if (admissionHandler == null) {
			completion.fail(new RaftUnavailableException("Admission is unavailable")); return;
		}
		if (!peer.equals(join.identity().nodeId())
			|| !identity.groupName().equals(join.identity().groupName())) {
			completion.fail(new SecurityException("Admission sender/group mismatch")); return;
		}
		admissionHandler.apply(join).onComplete(completion);
	}

	private void receiveLeave(String peer, LeaveRequest leaving, RaftRequestCompletion<LeaveResult> completion) {
		whenBound(() -> {
			if (leaveHandler == null || !installed) {
				completion.fail(new RaftUnavailableException("Removal is unavailable")); return;
			}
			if (!peer.equals(leaving.identity().nodeId())
				|| !identity.groupName().equals(leaving.identity().groupName())
				|| !Objects.equals(clusterId, leaving.clusterId())) {
				completion.fail(new SecurityException("Leave sender/group/cluster mismatch")); return;
			}
			leaveHandler.apply(leaving).onComplete(completion);
		}, completion);
	}

	private void receiveGenesis(String peer, Void ignored, RaftRequestCompletion<RaftGenesis> completion) {
		bootstrap.state().onComplete(result -> {
			if (result.failed()) completion.fail(result.cause());
			else if (result.result().genesis() == null) completion.fail(new RaftUnavailableException("Bootstrap pending"));
			else completion.succeed(result.result().genesis());
		});
	}

	private void receiveInstallation(String peer, RaftGenesis genesis, RaftRequestCompletion<Void> completion) {
		if (closed) { completion.fail(new CancellationException("Bootstrap closed")); return; }
		bootstrap.install(genesis, peer).onComplete(installed -> {
			if (installed.failed()) completion.fail(installed.cause()); else refreshBinding().onComplete(completion);
		});
	}

	private void whenBound(Runnable action, RaftRequestCompletion<?> completion) {
		refreshBinding().onComplete(result -> {
			if (result.failed()) completion.fail(result.cause());
			else if (!completion.isActive()) completion.fail(new CancellationException("Request expired"));
			else if (closed) completion.fail(new CancellationException("Transport closed"));
			else try { action.run(); } catch (Exception e) { completion.fail(e); }
		});
	}

	private void ensureListening() {
		ensureActive();
		if (listenFuture == null || !listenFuture.succeeded()) throw new IllegalStateException("Transport is not listening");
	}

	private void ensureActive() {
		if (closed) throw new IllegalStateException("Transport is closed");
	}
}
