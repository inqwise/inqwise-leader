package com.inqwise.leader;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;

/**
 * Context-owned automatic discovery, learner admission and voter replacement.
 * Owns the supplied store/HTTP transport and the participant it creates; discovery/DNS resources remain caller-owned.
 */
public final class MembershipController {
	private static final Logger LOG = LogManager.getLogger(MembershipController.class);
	private enum Status { NEW, STARTING, RUNNING, LEAVING, CLOSING, CLOSED }

	/**
	 * Optional local timing/resource bounds; desired voter count always comes from durable genesis.
	 * @param refreshIntervalMillis health and route refresh interval
	 * @param replacementDelayMillis continuous failure observation before replacement/learner eviction
	 * @param maxLearners bounded standby learner pool
	 * @param advertisedEndpoint callback address, or null to use a concrete listener host and actual bound port
	 */
	public record Options(long refreshIntervalMillis, long replacementDelayMillis, int maxLearners,
		NodeEndpoint advertisedEndpoint) {
		/**
		 * Validates controller bounds.
		 * @param refreshIntervalMillis polling interval
		 * @param replacementDelayMillis failure delay
		 * @param maxLearners pool limit
		 * @param advertisedEndpoint callback override
		 */
		public Options {
			if (refreshIntervalMillis < 1 || replacementDelayMillis < refreshIntervalMillis
				|| maxLearners < 1 || maxLearners > 16) throw new IllegalArgumentException("Invalid controller bounds");
		}
		/**
		 * Uses a one-second refresh, 30-second failure delay and two standby learners.
		 * @return default local controller options
		 */
		public static Options defaults() { return new Options(1000, 30000, 2, null); }
	}

	private final AsyncRaftStore store;
	private final RaftClusterTransport transport;
	private final NodeDiscovery discovery;
	private final Context context;
	private final Options options;
	private final RaftElection.Timing timing;
	private final RandomGenerator random;
	private final Map<String, Long> missingSince = new HashMap<>();
	private Status status = Status.NEW;
	private RaftIdentity identity;
	private VertxLeaderNode node;
	private Promise<Void> starting;
	private Future<Void> closeFuture;
	private long timer = -1;
	private long generation;
	private List<NodeEndpoint> cachedEndpoints = List.of();
	private long nextResolution;
	private boolean hasResolution;
	private boolean initialInstallationComplete;
	private long leaderTerm = -1;
	private Set<String> observedVoters = Set.of();
	private Set<String> retainedMembers = Set.of();
	private Handler<Leadership> leadershipHandler;
	private Handler<Throwable> exceptionHandler;
	private Leadership leadership = new Leadership(false, 0, null);
	private String lastReported;
	private boolean admissionSuspended;
	private Function<RaftElection.SnapshotPoint, Future<String>> snapshotProvider;
	private Function<RaftSnapshot, Future<Void>> snapshotRestorer;
	private Handler<RaftLogEntry> committedHandler;
	private final Set<String> departures = new HashSet<>();
	private long departureGeneration;
	private Promise<Void> leaving;
	private long leaveTimer = -1;
	private long leaveDeadline = -1;
	private boolean finishingLeave;
	private Throwable lastLeaveError;
	private Future<Void> passWork = Future.succeededFuture();

	/**
	 * Creates a controller with default local timing and bounds.
	 * @param store exclusively owned local store
	 * @param transport bootstrap-enabled transport on the store context
	 * @param discovery static/SRV discovery source; caller owns its underlying DNS resources
	 */
	public MembershipController(AsyncRaftStore store, RaftClusterTransport transport, NodeDiscovery discovery) {
		this(store, transport, discovery, Options.defaults());
	}

	/**
	 * Creates a controller with explicit local bounds.
	 * @param store exclusively owned store
	 * @param transport bootstrap-enabled transport
	 * @param discovery endpoint source
	 * @param options optional local timing/resource limits
	 */
	public MembershipController(AsyncRaftStore store, RaftClusterTransport transport, NodeDiscovery discovery, Options options) {
		this(store, transport, discovery, options, RaftElection.Timing.defaults(), RandomGenerator.getDefault());
	}

	MembershipController(AsyncRaftStore store, RaftClusterTransport transport, NodeDiscovery discovery, Options options,
		RaftElection.Timing timing, RandomGenerator random) {
		this.store = Objects.requireNonNull(store, "store"); this.transport = Objects.requireNonNull(transport, "transport");
		this.context = store.context();
		if (transport.context() != context || transport.bootstrapAuthorityId() == null) {
			throw new IllegalArgumentException("A bootstrap transport on the store context is required");
		}
		this.discovery = Objects.requireNonNull(discovery, "discovery"); this.options = Objects.requireNonNull(options, "options");
		this.timing = Objects.requireNonNull(timing, "timing"); this.random = Objects.requireNonNull(random, "random");
	}

	/**
	 * Registers the application's readiness handler before or after startup.
	 * @param handler readiness observer, or null to unregister
	 * @return this controller
	 */
	public MembershipController leadershipHandler(Handler<Leadership> handler) {
		context.runOnContext(v -> { leadershipHandler = handler; if (handler != null) notifyLeadership(leadership); });
		return this;
	}

	/**
	 * Registers retryable discovery, admission and protocol error observation.
	 * @param handler error observer, or null for debug logging
	 * @return this controller
	 */
	public MembershipController exceptionHandler(Handler<Throwable> handler) {
		context.runOnContext(v -> exceptionHandler = handler); return this;
	}

	/**
	 * Registers application recovery hooks before the controller starts; see {@link VertxLeaderNode#snapshotHandlers}.
	 * @param provider asynchronous producer for the exact requested committed index
	 * @param restorer idempotent asynchronous checkpoint restoration
	 * @param committed handler for subsequent committed entries, including recovery replay
	 * @return registration completion
	 */
	public Future<Void> snapshotHandlers(Function<RaftElection.SnapshotPoint, Future<String>> provider,
		Function<RaftSnapshot, Future<Void>> restorer, Handler<RaftLogEntry> committed) {
		return ContextDispatch.call(context, () -> {
			if (status != Status.NEW) return Future.failedFuture("Register snapshot hooks before startup");
			snapshotProvider = Objects.requireNonNull(provider, "provider");
			snapshotRestorer = Objects.requireNonNull(restorer, "restorer");
			committedHandler = Objects.requireNonNull(committed, "committed");
			return Future.succeededFuture();
		});
	}

	/**
	 * Opens local resources and starts explicit single-pass scheduling; completion is not leader/member readiness.
	 * @return listener/recovery startup completion
	 */
	public Future<Void> start() {
		return ContextDispatch.call(context, this::startOnContext);
	}

	private Future<Void> startOnContext() {
		if (starting != null && (status == Status.STARTING || status == Status.RUNNING)) return starting.future();
		if (status != Status.NEW) return Future.failedFuture("Create a new controller after close");
		status = Status.STARTING; long epoch = ++generation;
		starting = ContextDispatch.promise(context);
		store.open().compose(v -> store.identity()).compose(recovered -> {
			check(epoch); identity = recovered;
			if (options.advertisedEndpoint() != null && options.advertisedEndpoint().expectedNodeId() != null
				&& !identity.nodeId().equals(options.advertisedEndpoint().expectedNodeId())) {
				return Future.failedFuture("Advertised identity differs from persisted identity");
			}
			return transport.listen();
		}).compose(v -> transport.admissionHandler(this::admit))
			.compose(v -> transport.leaveHandler(request -> removeDeparting(request, false)))
			.onComplete(result -> context.runOnContext(v -> {
				if (epoch != generation || status != Status.STARTING) return;
				if (result.failed()) { starting.tryFail(result.cause()); report(result.cause()); close(); }
				else { status = Status.RUNNING; schedule(epoch, 1); starting.tryComplete(); }
			}));
		return starting.future();
	}

	/**
	 * Reads the local participant once durable assignment and recovery have completed.
	 * @return participant, or a failed future while awaiting admission/after close
	 */
	public Future<VertxLeaderNode> participant() {
		return ContextDispatch.call(context, () -> (status == Status.RUNNING || status == Status.LEAVING) && node != null
			? Future.succeededFuture(node) : Future.failedFuture("Participant is not ready"));
	}

	/**
	 * Stops application work, requests committed removal, then closes owned resources with a 30-second membership deadline.
	 * A timeout/failure reports an unknown removal outcome; it never implies quorum was reduced locally.
	 * @return completion after confirmed removal and cleanup, or failure after cleanup
	 */
	public Future<Void> leave() { return leave(30000); }

	/**
	 * Leaves through the current leader, retrying discovery/term changes within a bounded membership wait.
	 * Application leadership is suspended immediately. Accepted storage/restoration still drains during cleanup.
	 * Retained identity and genesis are preserved; restarting a new controller permits normal re-admission.
	 * @param timeoutMillis positive deadline for removal confirmation, excluding storage cleanup
	 * @return shared leave result; repeated calls do not restart the deadline
	 */
	public Future<Void> leave(long timeoutMillis) {
		return ContextDispatch.call(context, () -> leaveOnContext(timeoutMillis));
	}

	private Future<Void> leaveOnContext(long timeoutMillis) {
		if (timeoutMillis <= 0) return Future.failedFuture("A positive leave deadline is required");
		if (leaving != null) return leaving.future();
		if (status != Status.RUNNING) return Future.failedFuture("A running controller is required");
		status = Status.LEAVING; generation++;
		if (timer != -1) { context.owner().cancelTimer(timer); timer = -1; }
		leaving = ContextDispatch.promise(context);
		emitLeadership(new Leadership(false, leadership.term(), null));
		leaveDeadline = context.owner().setTimer(timeoutMillis, ignored -> {
			var timeout = new java.util.concurrent.TimeoutException("Leave timed out; removal outcome unknown");
			if (lastLeaveError != null) timeout.addSuppressed(lastLeaveError);
			finishLeave(timeout);
		});
		Future<Void> drained = node == null ? Future.succeededFuture() : node.drain();
		drained.compose(v -> transport.beginLeave())
			.compose(v -> node == null ? passWork.recover(cause -> Future.succeededFuture()) : Future.succeededFuture())
			.onComplete(result -> {
			if (status != Status.LEAVING || finishingLeave) return;
			if (result.failed()) finishLeave(result.cause()); else attemptLeave();
		});
		return leaving.future();
	}

	/**
	 * Cancels controller scheduling, stops participation and closes owned HTTP/storage resources.
	 * @return completion of cleanup, including accepted durable I/O
	 */
	public Future<Void> close() {
		return ContextDispatch.call(context, this::closeOnContext);
	}

	private Future<Void> closeOnContext() {
		if (closeFuture == null) {
			status = Status.CLOSING; generation++;
			cancelLeaveTimers();
			if (leaving != null && !finishingLeave) leaving.tryFail(new CancellationException("Leave canceled; removal outcome unknown"));
			emitLeadership(new Leadership(false, leadership.term(), null));
			if (timer != -1) context.owner().cancelTimer(timer);
			if (starting != null) starting.tryFail(new CancellationException("Controller closed"));
			var participantClosed = node == null ? store.close() : node.close();
			closeFuture = Future.join(participantClosed, transport.close()).<Void>mapEmpty().andThen(result -> {
				status = Status.CLOSED;
			});
		}
		return closeFuture;
	}

	private void cancelLeaveTimers() {
		if (leaveTimer != -1) context.owner().cancelTimer(leaveTimer);
		if (leaveDeadline != -1) context.owner().cancelTimer(leaveDeadline);
		leaveTimer = leaveDeadline = -1;
	}

	private void finishLeave(Throwable cause) {
		if (status != Status.LEAVING || finishingLeave) return;
		finishingLeave = true; cancelLeaveTimers();
		close().onComplete(closed -> {
			if (cause != null) {
				if (closed.failed() && closed.cause() != cause) cause.addSuppressed(closed.cause());
				leaving.tryFail(cause);
			} else if (closed.failed()) leaving.tryFail(closed.cause()); else leaving.tryComplete();
		});
	}

	private void attemptLeave() {
		if (status != Status.LEAVING || finishingLeave) return;
		store.bootstrapState().compose(binding -> {
			if (status != Status.LEAVING) return Future.failedFuture("Leave canceled");
			if (binding.genesis() == null) return Future.<LeaveResult>succeededFuture();
			if (!binding.installed()) return Future.failedFuture("Prepared genesis exists; removal is unconfirmed");
			if (node == null) return Future.failedFuture("Participant not ready; removal is unconfirmed");
			return node.controlMembershipView().compose(view -> {
				if (view.leadership().leader()) {
					return removeDeparting(new LeaveRequest(identity, binding.genesis().clusterId(), view.leadership().term()), true);
				}
				return transport.knownEndpoints().compose(known -> {
					var candidates = new LinkedHashMap<String, NodeEndpoint>();
					for (var endpoint : known) candidates.put(endpoint.host() + ":" + endpoint.port(), endpoint);
					for (var endpoint : cachedEndpoints) if (!identity.nodeId().equals(endpoint.expectedNodeId()) && candidates.size() < 64) {
						candidates.putIfAbsent(endpoint.host() + ":" + endpoint.port(), endpoint);
					}
					return findLeaveLeader(List.copyOf(candidates.values()), binding.genesis()).compose(transport::leaveCluster);
				});
			});
		}).onComplete(result -> {
			if (status != Status.LEAVING || finishingLeave) return;
			if (result.succeeded()) { finishLeave(null); return; }
			lastLeaveError = result.cause(); report(result.cause());
			leaveTimer = context.owner().setTimer(Math.min(1000, options.refreshIntervalMillis()), ignored -> {
				leaveTimer = -1; attemptLeave();
			});
		});
	}

	private Future<NodeEndpoint> findLeaveLeader(List<NodeEndpoint> candidates, RaftGenesis genesis) {
		if (candidates.isEmpty()) return Future.failedFuture("No known leader endpoints");
		var found = ContextDispatch.<NodeEndpoint>promise(context); int[] remaining = {candidates.size()};
		for (var candidate : candidates) transport.memberStatus(candidate).onComplete(observed -> {
			if (status != Status.LEAVING || finishingLeave) { found.tryFail("Leave canceled"); return; }
			if (observed.succeeded() && observed.result().ready() && observed.result().genesis().equals(genesis)) {
				found.tryComplete(candidate);
			}
			if (--remaining[0] == 0) found.tryFail("No ready leader for graceful removal");
		});
		return found.future();
	}

	private Future<LeaveResult> removeDeparting(LeaveRequest request, boolean local) {
		long epoch = generation;
		if (node == null || admissionSuspended || (local ? status != Status.LEAVING : status != Status.RUNNING)) {
			return Future.failedFuture("Removal is unavailable");
		}
		return store.bootstrapState().compose(binding -> {
			if (!request.identity().groupName().equals(identity.groupName())
				|| !request.clusterId().equals(binding.genesis().clusterId())) return Future.failedFuture("Leave binding mismatch");
			return node.controlMembershipView();
		}).compose(view -> {
			if (epoch != generation || (local ? status != Status.LEAVING : status != Status.RUNNING)
				|| !view.leadership().leader() || view.leadership().term() != request.leaderTerm() || view.busy()
				|| view.membership().effective().joint() || view.membership().configurationIndex() > view.commitIndex()) {
				return Future.failedFuture("A settled ready leader in the requested term is required");
			}
			String departing = request.identity().nodeId(); var configuration = view.membership().effective();
			Future<?> removal;
			if (!configuration.members().contains(departing)) removal = Future.succeededFuture();
			else {
				departures.removeIf(id -> !configuration.members().contains(id));
				if (departures.add(departing)) departureGeneration++;
				if (configuration.learners().contains(departing)) {
					removal = node.removeLearner(departing, request.leaderTerm(), view.membership().configurationIndex());
				} else {
					var desired = new java.util.TreeSet<>(configuration.voters()); desired.remove(departing);
					configuration.learners().stream().filter(id -> !departures.contains(id))
						.filter(view.responsiveMembers()::contains)
						.filter(id -> view.matchIndex().getOrDefault(id, 0L) >= view.commitIndex()).sorted().findFirst()
						.ifPresent(desired::add);
					if (desired.isEmpty()) return Future.failedFuture("The final voter needs a caught-up replacement");
					removal = node.changeVoters(desired, request.leaderTerm(), view.membership().configurationIndex());
				}
			}
			return removal.compose(v -> node.controlMembershipView()).map(committed -> {
				if (epoch != generation || committed.leadership().term() != request.leaderTerm()
					|| (!local && !committed.leadership().leader()) || committed.membership().committed().joint()
					|| committed.membership().committed().members().contains(departing)
					|| committed.membership().effective().members().contains(departing)) {
					throw new IllegalStateException("Removal confirmation changed; retry with the current leader");
				}
				return new LeaveResult(identity, request.clusterId(), request.leaderTerm(), departing,
					committed.membership().committed(), committed.membership().committedConfigurationIndex(), committed.commitIndex());
			});
		});
	}

	private void schedule(long epoch, long delay) {
		if (status != Status.RUNNING || epoch != generation) return;
		timer = context.owner().setTimer(delay, ignored -> {
			timer = -1;
			Future<Void> work;
			try { work = pass(epoch); } catch (Exception e) { work = Future.failedFuture(e); }
			passWork = work;
			work.onComplete(result -> context.runOnContext(v -> {
				if (status != Status.RUNNING || epoch != generation) return;
				if (result.failed()) report(result.cause());
				schedule(epoch, options.refreshIntervalMillis());
			}));
		});
	}

	private Future<Void> pass(long epoch) {
		return refreshDiscovery(epoch).compose(v -> discoverPeers(epoch))
			.compose(found -> followMembership(epoch, found));
	}

	private Future<RaftClusterTransport.DiscoveryResult> discoverPeers(long epoch) {
		check(epoch);
		Future<Void> retained = node == null ? Future.succeededFuture() : node.membershipView().map(view -> {
			retainedMembers = view.membership().effective().members(); return (Void) null;
		});
		return retained.compose(v -> transport.pruneRoutes(retainedMembers, options.replacementDelayMillis()))
			.compose(v -> transport.knownEndpoints()).compose(known -> {
				check(epoch);
				var merged = new LinkedHashMap<String, NodeEndpoint>();
				for (var endpoint : known) if (retainedMembers.contains(endpoint.expectedNodeId())) {
					merged.put(endpoint.host() + ":" + endpoint.port(), endpoint);
				}
				for (var endpoint : cachedEndpoints) if (merged.size() < 64) {
					merged.putIfAbsent(endpoint.host() + ":" + endpoint.port(), endpoint);
				}
				return transport.discover(List.copyOf(merged.values()));
			});
	}

	private Future<Void> followMembership(long epoch, RaftClusterTransport.DiscoveryResult found) {
		check(epoch);
		Set<String> clusters = new HashSet<>(found.conflicts());
		for (var peer : found.peers()) if (peer.clusterId() != null) clusters.add(peer.clusterId());
		if (clusters.size() > 1 || !found.conflicts().isEmpty()) {
			admissionSuspended = true; return Future.failedFuture("Conflicting cluster identities");
		}
		admissionSuspended = false;
		return store.bootstrapState().compose(binding -> {
			check(epoch);
			if (node == null && binding.genesis() != null) retainedMembers = binding.genesis().voters();
			if (binding.genesis() != null && !clusters.isEmpty() && !clusters.contains(binding.genesis().clusterId())) {
				return Future.failedFuture("Discovered cluster differs from durable genesis");
			}
			if (binding.installed()) return ensureParticipant(epoch)
				.compose(v -> maintainInstalledMembership(epoch, binding.genesis(), found.peers()));
			if (identity.nodeId().equals(transport.bootstrapAuthorityId())) return bootstrapInitialVoters(epoch);
			return findLeaderAndJoin(epoch, found.peers());
		});
	}

	private Future<Void> bootstrapInitialVoters(long epoch) {
		if (initialInstallationComplete) return Future.succeededFuture();
		return transport.bootstrap(cachedEndpoints).compose(result -> {
			check(epoch); initialInstallationComplete = result.failures().isEmpty();
			// Formation may have installed the local assignment; read that durable outcome before participating.
			return store.bootstrapState().compose(binding -> binding.installed()
				? ensureParticipant(epoch) : Future.succeededFuture());
		});
	}

	private Future<Void> maintainInstalledMembership(long epoch, RaftGenesis genesis, List<DiscoveredPeer> peers) {
		if (identity.nodeId().equals(transport.bootstrapAuthorityId()) && !initialInstallationComplete) {
			return node.membershipView().compose(view -> {
				if (view.commitIndex() > 0) {
					initialInstallationComplete = true; return reconcile(epoch, genesis);
				}
				return transport.bootstrap(cachedEndpoints).compose(result -> {
					check(epoch); initialInstallationComplete = result.failures().isEmpty();
					return reconcile(epoch, genesis);
				});
			});
		}
		return node.membershipView().compose(view -> view.leadership().leader()
			? reconcile(epoch, genesis) : findLeaderAndJoin(epoch, peers));
	}

	private Future<Void> refreshDiscovery(long epoch) {
		long now = System.nanoTime();
		if (hasResolution && now - nextResolution < 0) return Future.succeededFuture();
		Future<NodeDiscovery.Result> resolving;
		try { resolving = Objects.requireNonNull(discovery.resolve(), "discovery future"); } catch (Exception e) { resolving = Future.failedFuture(e); }
		return ContextDispatch.relay(context, resolving).timeout(5000, TimeUnit.MILLISECONDS).map(result -> {
			check(epoch); cachedEndpoints = result.endpoints(); hasResolution = true;
			long wait = Math.min(result.refreshAfterMillis(), TimeUnit.HOURS.toMillis(24));
			nextResolution = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait);
			if (!result.failures().isEmpty()) report(new IllegalStateException("DNS target failures: " + result.failures()));
			return (Void) null;
		}).recover(cause -> {
			check(epoch); report(cause); nextResolution = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.refreshIntervalMillis());
			hasResolution = true; return Future.succeededFuture();
		});
	}

	private Future<Void> findLeaderAndJoin(long epoch, List<DiscoveredPeer> peers) {
		var assigned = peers.stream().filter(peer -> peer.clusterId() != null).toList();
		List<Future<MemberStatus>> statuses = assigned.stream().map(peer -> transport.memberStatus(peer.endpoint())).toList();
		return Future.join(statuses).transform(ignored -> {
			check(epoch);
			MemberStatus leader = null; NodeEndpoint endpoint = null;
			for (int i = 0; i < statuses.size(); i++) if (statuses.get(i).succeeded() && statuses.get(i).result().ready()) {
				if (leader != null) return Future.failedFuture("Multiple ready leader claims; admission suspended");
				leader = statuses.get(i).result(); endpoint = assigned.get(i).endpoint();
			}
			if (leader == null) return Future.succeededFuture();
			if (node != null && leader.configuration().members().contains(identity.nodeId())) return Future.succeededFuture();
			if (node == null && leader.genesis().voters().contains(identity.nodeId())) {
				var authority = peers.stream().filter(peer -> peer.identity().nodeId().equals(transport.bootstrapAuthorityId()))
					.findFirst();
				return authority.isEmpty() ? Future.succeededFuture() : transport.fetchBootstrap(authority.get().endpoint())
					.compose(genesis -> { check(epoch); return ensureParticipant(epoch); });
			}
			Future<NodeEndpoint> advertised = options.advertisedEndpoint() == null ? transport.localEndpoint()
				: Future.succeededFuture(options.advertisedEndpoint());
			NodeEndpoint expected = endpoint;
			return advertised.compose(local -> { check(epoch); return transport.joinCluster(expected, local); })
				.compose(genesis -> { check(epoch); return ensureParticipant(epoch); });
		});
	}

	private Future<Void> ensureParticipant(long epoch) {
		check(epoch); if (node != null) return Future.succeededFuture();
		return VertxLeaderNode.fromStore(store, timing, random, transport).compose(created -> {
			if (epoch != generation || status != Status.RUNNING) return created.close().compose(v -> Future.failedFuture("Controller closed"));
			node = created; node.exceptionHandler(this::report); node.leadershipHandler(value -> {
				if (status == Status.RUNNING) emitLeadership(value);
			});
			node.committedHandler(committedHandler);
			Future<Void> hooks = snapshotProvider == null ? Future.succeededFuture()
				: node.snapshotHandlers(snapshotProvider, snapshotRestorer);
			return hooks.compose(v -> transport.attachNode(node)).compose(v -> { check(epoch); return node.start(); });
		});
	}

	private Future<Void> reconcile(long epoch, RaftGenesis genesis) {
		return node.membershipView().compose(view -> {
			check(epoch);
			if (!view.leadership().leader()) { leaderTerm = -1; missingSince.clear(); return Future.succeededFuture(); }
			var configuration = view.membership().effective();
			if (leaderTerm != view.leadership().term() || !observedVoters.equals(configuration.voters())) {
				missingSince.clear(); leaderTerm = view.leadership().term(); observedVoters = configuration.voters();
			}
			long now = System.nanoTime(); missingSince.keySet().retainAll(configuration.members());
			for (String id : configuration.members()) {
				if (view.responsiveMembers().contains(id)) missingSince.remove(id); else missingSince.putIfAbsent(id, now);
			}
			var eligible = new HashSet<>(view.responsiveMembers()); eligible.removeAll(departures);
			var policyView = new MembershipView(view.leadership(), view.membership(), view.matchIndex(), view.commitIndex(),
				eligible, view.busy());
			var decision = MembershipPolicy.plan(identity.nodeId(), policyView, genesis.targetVoterCount(),
				missingSince, now, TimeUnit.MILLISECONDS.toNanos(options.replacementDelayMillis()));
			if (decision instanceof MembershipPolicy.ChangeVoters change) {
				return node.changeVoters(change.voters(), view.leadership().term(),
					view.membership().configurationIndex()).mapEmpty();
			}
			if (decision instanceof MembershipPolicy.RemoveLearner removal) {
				return node.removeLearner(removal.identity(), view.leadership().term(),
					view.membership().configurationIndex()).mapEmpty();
			}
			return transport.pruneRoutes(configuration.members(), options.replacementDelayMillis());
		});
	}

	private Future<MemberStatus> admit(JoinRequest join) {
		long epoch = generation; check(epoch); long departureEpoch = departureGeneration;
		return node.membershipView().compose(before -> {
			checkAdmission(before, join);
			return transport.verifyPeer(new NodeEndpoint(join.endpoint().host(), join.endpoint().port(), join.identity().nodeId()));
		}).compose(peer -> {
			check(epoch);
			if (peer.departing() || departureEpoch != departureGeneration) return Future.failedFuture("Departure superseded admission");
			departures.remove(join.identity().nodeId());
			return node.membershipView().compose(current -> {
				checkAdmission(current, join);
				return store.bootstrapState().compose(binding -> {
					var genesis = binding.genesis();
					if ((genesis.voters().contains(join.identity().nodeId()) && join.clusterId() == null) || (join.clusterId() != null
						&& !join.clusterId().equals(genesis.clusterId())) || (peer.clusterId() != null
						&& !peer.clusterId().equals(genesis.clusterId()))) return Future.failedFuture("Incompatible cluster/initial voter admission");
					Future<?> committed = current.membership().effective().learners().contains(join.identity().nodeId())
						? Future.succeededFuture() : node.addLearner(join.identity().nodeId(), join.leaderTerm(), current.membership().configurationIndex());
					return committed.compose(v -> { check(epoch); return transport.localMemberStatus(); }).map(status -> {
						if (!status.ready() || status.term() != join.leaderTerm()) throw new IllegalStateException("Leader changed during admission");
						return status;
					});
				});
			});
		});
	}

	private void checkAdmission(MembershipView view, JoinRequest join) {
		if (status != Status.RUNNING || admissionSuspended || node == null || !view.leadership().leader()
			|| view.leadership().term() != join.leaderTerm() || view.busy() || view.membership().effective().joint()
			|| view.membership().configurationIndex() > view.commitIndex()
			|| view.membership().effective().voters().contains(join.identity().nodeId())
			|| (!view.membership().effective().learners().contains(join.identity().nodeId())
				&& (view.membership().effective().learners().size() >= options.maxLearners()
					|| view.membership().effective().members().size() >= RaftConfiguration.MAX_MEMBERS))) {
			throw new IllegalStateException("Leader is not accepting this learner admission");
		}
	}

	private void check(long epoch) {
		if (generation != epoch || (status != Status.RUNNING && status != Status.STARTING)) throw new CancellationException("Controller closed");
	}

	private void emitLeadership(Leadership value) {
		if (status == Status.LEAVING && value.leader()) return;
		if (value.equals(leadership)) return;
		leadership = value; notifyLeadership(value);
	}

	private void notifyLeadership(Leadership value) {
		if (leadershipHandler == null) return;
		try { leadershipHandler.handle(value); } catch (Exception e) { report(e); }
	}

	private void report(Throwable cause) {
		String key = cause.getClass().getName() + ":" + cause.getMessage();
		if (key.equals(lastReported)) return; lastReported = key;
		if (exceptionHandler == null) { LOG.debug("Membership controller will retry", cause); return; }
		try { exceptionHandler.handle(cause); } catch (Exception e) { LOG.warn("Membership error handler failed", e); }
	}
}
