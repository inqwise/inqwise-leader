package com.inqwise.leader;

import java.util.Objects;

import java.util.function.Function;
import java.util.random.RandomGenerator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.dns.DnsClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerOptions;

/**
 * Opt-in Vert.x lifecycle facade for configured Raft storage, HTTP, discovery and automatic membership.
 * Owns resources it creates, including an SRV DNS client; the application's Vert.x instance remains caller-owned.
 */
public final class LeaderCoordinator {
	private static final Logger LOG = LogManager.getLogger(LeaderCoordinator.class);
	private static final Handler<Throwable> DEFAULT_EXCEPTION_HANDLER =
		cause -> LOG.error("Unhandled coordinator exception", cause);
	private enum Status { NEW, STARTING, RUNNING, FAILED, LEAVING, CLOSING, CLOSED }
	// One shared owner for lifecycle state; private operations already run on it.
	private final Context context;
	private final LeaderOptions options;
	private final HttpServerOptions serverOptions;
	private final HttpClientOptions clientOptions;
	private AsyncRaftStore store;
	private Future<Void> initializationFuture;
	private Function<RaftElection.SnapshotPoint, Future<String>> snapshotProvider;
	private Function<RaftSnapshot, Future<Void>> snapshotRestorer;
	private Handler<RaftLogEntry> committedHandler;
	private MembershipController controller;
	private RaftClusterTransport http;
	private DnsClient dns;
	private RaftIdentity identity;
	private Status status = Status.NEW;
	private Leadership leadership = new Leadership(false, 0, null);
	private Handler<Leadership> leadershipHandler;
	private Handler<Throwable> exceptionHandler = DEFAULT_EXCEPTION_HANDLER;
	private Future<Void> startFuture;
	private String listenHost;
	private int listenPort;
	private Future<Void> leaveFuture;
	private Future<Void> cleanupFuture;
	private Future<Void> closeFuture;

	private LeaderCoordinator(Context context, LeaderOptions options,
		HttpServerOptions serverOptions, HttpClientOptions clientOptions) {
		this.context = context; this.options = options;
		this.serverOptions = serverOptions; this.clientOptions = clientOptions;
	}

	/**
	 * Creates an unopened local plaintext singleton: group "default", identity/authority "node-a", port 8080.
	 * Uses the group-derived persistent directory. Explicit options retain their default target of three voters.
	 * @param vertx application-owned Vert.x instance
	 * @return unopened development coordinator
	 */
	public static LeaderCoordinator create(io.vertx.core.Vertx vertx) {
		return create(vertx, LeaderOptions.builder().groupName("default").nodeId("node-a")
			.bootstrapAuthorityId("node-a").targetVoterCount(1).build());
	}

	/**
	 * Creates an unopened plaintext coordinator with self-reported, unauthenticated peer identities.
	 * @param vertx application-owned Vert.x instance
	 * @param options immutable startup configuration
	 * @return unopened coordinator
	 */
	public static LeaderCoordinator create(io.vertx.core.Vertx vertx, LeaderOptions options) {
		return create(vertx, options, new HttpServerOptions(), new HttpClientOptions());
	}

	/**
	 * Creates an unopened coordinator without filesystem or network I/O. Register handlers, then call {@link #listen()}.
	 * Accepts validated immutable Raft options and defensively copies HTTP options.
	 * Storage recovery, credential validation and resource acquisition occur asynchronously during start.
	 * @param vertx application-owned Vert.x instance
	 * @param options immutable Raft options created through LeaderOptions.builder()
	 * @param serverOptions listener configuration; host/port come from listen()
	 * @param clientOptions client configuration; TLS mode must match the server, with verified mutual TLS when enabled
	 * @return unopened coordinator
	 */
	public static LeaderCoordinator create(io.vertx.core.Vertx vertx, LeaderOptions options,
		HttpServerOptions serverOptions, HttpClientOptions clientOptions) {
		Context context = Objects.requireNonNull(vertx, "vertx").getOrCreateContext();
		if (!context.isEventLoopContext()) throw new IllegalArgumentException("An event-loop context is required");
		return new LeaderCoordinator(context, Objects.requireNonNull(options, "options"),
			new HttpServerOptions(Objects.requireNonNull(serverOptions, "serverOptions")),
			new HttpClientOptions(Objects.requireNonNull(clientOptions, "clientOptions")));
	}

	private Future<Void> initialize() {
		if (status != Status.STARTING) return Future.failedFuture("Startup canceled");
		return DataDirectories.resolve(context, options).compose(directory -> {
			if (status != Status.STARTING) return Future.failedFuture("Startup canceled");
			store = VertxRaftStore.create(context.owner(), directory, options.getGroupName(), options.getNodeId());
			serverOptions.setHost(listenHost).setPort(listenPort);
			var bootstrap = new RaftBootstrap(store, options.getBootstrapAuthorityId(), options.getTargetVoterCount());
			return HttpRaftTransport.forBootstrap(bootstrap, serverOptions, clientOptions);
		}).compose(transport -> {
			http = transport;
			return store.identity();
		}).compose(recovered -> {
			if (!serverOptions.isSsl()) return Future.succeededFuture(recovered);
			return context.executeBlocking(() -> {
				RaftTlsIdentity.validateCredentials(context.owner(), serverOptions.getKeyCertOptions(), recovered.nodeId());
				RaftTlsIdentity.validateCredentials(context.owner(), clientOptions.getKeyCertOptions(), recovered.nodeId());
				return recovered;
			}, true);
		}).compose(recovered -> {
			identity = recovered;
			if (status != Status.STARTING) return Future.failedFuture("Startup canceled");
			NodeDiscovery discovery = switch (options.getDiscovery()) {
				case DiscoveryOptions.Static contacts -> NodeDiscovery.staticEndpoints(contacts.endpoints());
				case DiscoveryOptions.Srv service -> {
					dns = context.owner().createDnsClient(service.dnsOptions());
					yield new SrvNodeDiscovery(context, dns, service.name());
				}
			};
			controller = new MembershipController(store, http, discovery,
				options.getMembershipOptions(listenPort), options.getTiming(), RandomGenerator.getDefault());
			controller.leadershipHandler(this::observeLeadership).exceptionHandler(this::report);
			return snapshotProvider == null ? Future.succeededFuture()
				: controller.snapshotHandlers(snapshotProvider, snapshotRestorer, committedHandler);
		});
	}

	/**
	 * Returns the context owning this coordinator and all callbacks.
	 * @return owner context
	 */
	Context context() { return context; }
	/**
	 * Returns the immutable startup configuration. Use toBuilder() to derive different options.
	 * @return immutable startup options
	 */
	public LeaderOptions options() { return options; }
	/**
	 * Reads the persisted identity after successful startup, including after shutdown.
	 * @return immutable identity
	 */
	public RaftIdentity identity() {
		if (identity == null) throw new IllegalStateException("Identity is unavailable before storage recovery");
		return identity;
	}
	/**
	 * Registers local application readiness; receives the current observation on registration.
	 * @param handler callback, or null to unregister
	 * @return this coordinator
	 */
	public LeaderCoordinator leadershipHandler(Handler<Leadership> handler) {
		context.runOnContext(v -> { leadershipHandler = handler; if (handler != null) notifyLeadership(leadership); });
		return this;
	}
	/**
	 * Registers asynchronous operation/subscriber error observation.
	 * Defaults to ERROR logging with the cause when no handler is registered.
	 * @param handler callback, or null to restore the default logging handler
	 * @return this coordinator
	 */
	public LeaderCoordinator exceptionHandler(Handler<Throwable> handler) {
		context.runOnContext(v -> exceptionHandler = handler == null ? DEFAULT_EXCEPTION_HANDLER : handler); return this;
	}
	/**
	 * Registers application checkpoint production, restoration and retained-entry delivery before startup.
	 * @param provider asynchronous producer for an exact committed boundary
	 * @param restorer idempotent asynchronous application restoration
	 * @param committed committed-entry callback, including recovery replay
	 * @return registration completion on the owning context
	 */
	public Future<Void> snapshotHandlers(Function<RaftElection.SnapshotPoint, Future<String>> provider,
		Function<RaftSnapshot, Future<Void>> restorer, Handler<RaftLogEntry> committed) {
		return ContextDispatch.call(context, () -> {
			if (status != Status.NEW) return Future.failedFuture("Register application hooks before startup");
			Objects.requireNonNull(provider, "provider"); Objects.requireNonNull(restorer, "restorer");
			Objects.requireNonNull(committed, "committed");
			snapshotProvider = provider; snapshotRestorer = restorer; committedHandler = committed;
			return Future.succeededFuture();
		});
	}
	/**
	 * Recovers storage, validates credentials and starts listener/discovery and participation.
	 * Completion is not formation or leader readiness. Startup failure releases acquired resources.
	 * @return shared startup result
	 */
	public Future<Void> listen() { return listen(NodeEndpoint.DEFAULT_PORT); }

	/**
	 * @param port listener port (1..65535)
	 * @return local startup result, not leader readiness
	 */
	public Future<Void> listen(int port) { return listen(port, "127.0.0.1"); }

	/**
	 * Recovers and starts participation at an IPv4 listener. Repeating the same binding shares startup;
	 * a different binding requires a new coordinator. Wildcard binding requires an advertised endpoint.
	 * @param port listener port (1..65535)
	 * @param host IPv4 address or 0.0.0.0
	 * @return local startup result, not leader readiness
	 */
	public Future<Void> listen(int port, String host) {
		return ContextDispatch.call(context, () -> listenOnContext(port, host));
	}

	private Future<Void> listenOnContext(int port, String host) {
		new NodeEndpoint(host.equals("0.0.0.0") ? "127.0.0.1" : host, port, null);
		if (host.equals("0.0.0.0") && options.getAdvertisedEndpoint() == null) {
			return Future.failedFuture("Wildcard listener requires advertisedEndpoint");
		}
		if (status == Status.STARTING || status == Status.RUNNING) {
			return port == listenPort && host.equals(listenHost) ? startFuture
				: Future.failedFuture("Coordinator is already listening or starting at another address");
		}
		if (status != Status.NEW) return Future.failedFuture("Create a new coordinator after shutdown/failure");
		listenHost = host; listenPort = port;
		status = Status.STARTING;
		try { initializationFuture = initialize(); }
		catch (Exception e) { initializationFuture = Future.failedFuture(e); }
		startFuture = initializationFuture.compose(ignored -> status == Status.STARTING
			? controller.start() : Future.failedFuture("Startup canceled")).transform(result -> {
			if (result.succeeded()) {
				if (status == Status.STARTING) status = Status.RUNNING;
				return Future.succeededFuture();
			}
			if (status == Status.STARTING) status = Status.FAILED;
			return cleanup().transform(cleaned -> {
				if (cleaned.failed() && cleaned.cause() != result.cause()) result.cause().addSuppressed(cleaned.cause());
				return Future.failedFuture(result.cause());
			});
		});
		return startFuture;
	}
	/**
	 * Reads application readiness, including while formation is pending or after shutdown.
	 * @return current local observation
	 */
	public Future<Leadership> leadership() {
		return ContextDispatch.call(context, () -> Future.succeededFuture(leadership));
	}
	/**
	 * Reads the participant's current fencing epoch after rechecking application leadership readiness.
	 * Present it to each destination before starting leader work and with every subsequent side effect.
	 * @return token scoped to the durable group/cluster; fails before readiness, while leaving or after shutdown
	 */
	public Future<LeadershipToken> fencingToken() {
		return ContextDispatch.call(context, () -> {
			if (status != Status.RUNNING || controller == null) return Future.failedFuture("A running coordinator is required");
			return controller.participant().compose(VertxLeaderNode::fencingToken).map(token -> {
				if (status != Status.RUNNING) throw new IllegalStateException("Coordinator stopped before token delivery");
				return token;
			});
		});
	}

	/**
	 * Reads the participant after admission/recovery for advanced proposals and diagnostics.
	 * @return current participant; fails while unavailable
	 */
	public Future<VertxLeaderNode> participant() {
		return ContextDispatch.call(context, () -> controller == null
			? Future.failedFuture("Participant is unavailable before startup") : controller.participant());
	}
	/**
	 * Reads the concrete configured listener endpoint after startup.
	 * @return listener address; wildcard listeners require the configured advertised endpoint
	 */
	public Future<NodeEndpoint> localEndpoint() {
		return ContextDispatch.call(context, () -> {
			if (status != Status.RUNNING && status != Status.LEAVING) return Future.failedFuture("Listener is not running");
			return options.getMembershipOptions(listenPort).advertisedEndpoint() == null ? http.localEndpoint()
				: Future.succeededFuture(options.getMembershipOptions(listenPort).advertisedEndpoint());
		});
	}
	/**
	 * Drains application work and leaves using the configured membership deadline, then closes owned resources.
	 * @return confirmed removal and cleanup, or failure with an unknown removal outcome
	 */
	public Future<Void> leave() { return leave(options.getLeaveTimeoutMillis()); }
	/**
	 * Leaves with an explicit membership deadline. Accepted storage/restoration can extend cleanup beyond this wait.
	 * @param timeoutMillis positive removal deadline
	 * @return shared leave result
	 */
	public Future<Void> leave(long timeoutMillis) {
		return ContextDispatch.call(context, () -> leaveOnContext(timeoutMillis));
	}

	private Future<Void> leaveOnContext(long timeoutMillis) {
		if (timeoutMillis <= 0) return Future.failedFuture("A positive leave deadline is required");
		if (leaveFuture != null) return leaveFuture;
		if (status != Status.RUNNING) return Future.failedFuture("A running coordinator is required");
		status = Status.LEAVING; observeLeadership(new Leadership(false, leadership.term(), null));
		leaveFuture = controller.leave(timeoutMillis).transform(removed -> cleanup().transform(cleaned -> {
			status = Status.CLOSED;
			if (removed.failed()) {
				if (cleaned.failed() && cleaned.cause() != removed.cause()) removed.cause().addSuppressed(cleaned.cause());
				return Future.failedFuture(removed.cause());
			}
			return cleaned.failed() ? Future.failedFuture(cleaned.cause()) : Future.succeededFuture();
		}));
		return leaveFuture;
	}
	/**
	 * Stops locally, canceling any graceful leave, and closes only coordinator-owned resources.
	 * @return completion after accepted I/O and DNS cleanup
	 */
	public Future<Void> close() {
		return ContextDispatch.call(context, this::closeOnContext);
	}

	private Future<Void> closeOnContext() {
		if (closeFuture == null) {
			status = Status.CLOSING; observeLeadership(new Leadership(false, leadership.term(), null));
			closeFuture = (initializationFuture == null ? cleanup()
				: initializationFuture.transform(ignored -> cleanup())).andThen(result -> status = Status.CLOSED);
		}
		return closeFuture;
	}

	private Future<Void> cleanup() {
		if (cleanupFuture == null) {
			Future<Void> participation = controller != null ? controller.close()
				: store == null ? Future.succeededFuture() : store.close();
			Future<Void> network = controller != null || http == null ? Future.succeededFuture() : http.close();
			Future<Void> resolution = dns == null ? Future.succeededFuture() : dns.close();
			cleanupFuture = Future.join(participation, network, resolution).mapEmpty();
		}
		return cleanupFuture;
	}
	private void observeLeadership(Leadership value) {
		if (value.leader() && (status == Status.LEAVING || status == Status.CLOSING || status == Status.CLOSED)) return;
		if (!value.equals(leadership)) { leadership = value; notifyLeadership(value); }
	}
	private void notifyLeadership(Leadership value) {
		if (leadershipHandler != null) try { leadershipHandler.handle(value); } catch (Exception e) { report(e); }
	}
	private void report(Throwable cause) {
		try { exceptionHandler.handle(cause); } catch (Exception e) { LOG.warn("Coordinator exception handler failed", e); }
	}
}
