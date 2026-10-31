package com.inqwise.leader;

import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
 * Context-confined adapter for Raft elections, replication and serialized membership transitions. Storage, timers and transport completions reenter the
 * store's owning context. The legacy {@link LeaderConsensus} API remains separate.
 */
public final class VertxLeaderNode {
	private static final Logger LOG = LogManager.getLogger(VertxLeaderNode.class);
	private enum Status { NEW, STARTING, RUNNING, FAILED, CLOSING, CLOSED }
	private record Sent(RaftElection.Message request, long sentAt) {}
	private final AsyncRaftStore store;
	private final Context context;
	private final String clusterId;
	private final Set<String> voters;
	private final RaftElection.Timing timing;
	private final RandomGenerator random;
	private final RaftTransport transport;
	private final Map<RaftElection.ScheduleTimer, Long> timers = new IdentityHashMap<>();
	private final Map<String, Sent> requests = new HashMap<>();
	private final Map<String, Long> acknowledgements = new HashMap<>();
	private Status status = Status.NEW;
	private RaftElection engine;
	private RaftIdentity identity;
	private boolean learnerAdmission;
	private RaftConfiguration trackedConfiguration;
	private Promise<RaftConfiguration> membershipChange;
	private RaftConfiguration expectedMembership;
	private long membershipTerm;
	private Handler<Leadership> leadershipHandler;
	private Handler<Throwable> exceptionHandler;
	private Handler<RaftLogEntry> committedHandler;
	private Leadership leadership = new Leadership(false, 0, null);
	private Promise<Void> starting;
	private Future<Void> closeFuture;
	private long healthTimer = -1;
	private long observedTerm;
	private long trackedTerm;
	private Promise<RaftLogEntry> proposal;
	private RaftLogEntry proposedEntry;
	private static final int COMPACTION_THRESHOLD = 128;
	private Function<RaftElection.SnapshotPoint, Future<String>> snapshotProvider;
	private Function<RaftSnapshot, Future<Void>> snapshotRestorer = checkpoint -> checkpoint.data().isEmpty()
		? Future.succeededFuture() : Future.failedFuture(new IllegalStateException("An application snapshot restorer is required"));
	private Future<Void> applicationBarrier = Future.succeededFuture();
	private Promise<RaftSnapshot> compaction;
	private RaftSnapshot preparedSnapshot;
	private RaftElection.WriteState compactionWrite;
	private boolean compactionScheduled;
	private long nextAutomaticCompaction;
	private boolean draining;
	private boolean protocolReady;

	/**
	 * Creates an unopened node; all voters must share the same fixed set and cluster identity.
	 * @param store asynchronous durable store, exclusively owned by this node
	 * @param clusterId agreed cluster identity (not discovered or generated here)
	 * @param voters fixed voter identities including this node
	 * @param timing election and heartbeat timing
	 * @param random election jitter source, used only on the owning context
	 * @param transport authenticated asynchronous message transport
	 */
	public VertxLeaderNode(AsyncRaftStore store, String clusterId, Set<String> voters,
		RaftElection.Timing timing, RandomGenerator random, RaftTransport transport) {
		this.store = Objects.requireNonNull(store, "store");
		this.context = Objects.requireNonNull(store.context(), "context");
		if (!context.isEventLoopContext()) throw new IllegalArgumentException("An event-loop context is required");
		this.clusterId = Objects.requireNonNull(clusterId, "clusterId");
		if (clusterId.isBlank()) throw new IllegalArgumentException("clusterId must not be blank");
		this.voters = Set.copyOf(voters);
		if (this.voters.isEmpty()) throw new IllegalArgumentException("At least one voter is required");
		this.voters.forEach(RaftIdentity::validateNodeId);
		this.timing = Objects.requireNonNull(timing, "timing");
		this.random = Objects.requireNonNull(random, "random");
		this.transport = Objects.requireNonNull(transport, "transport");
	}

	/**
	 * Creates a participant from durable configuration using default election timing and jitter.
	 * @param store exclusively owned local store
	 * @param transport authenticated transport
	 * @return unopened participant; call start after routes are ready
	 */
	public static Future<VertxLeaderNode> fromStore(AsyncRaftStore store, RaftTransport transport) {
		return fromStore(store, RaftElection.Timing.defaults(), RandomGenerator.getDefault(), transport);
	}

	/**
	 * Creates a node from its installed durable configuration, without forming or changing a cluster.
	 * @param store exclusively owned local store
	 * @param timing election timing
	 * @param random election jitter source
	 * @param transport authenticated transport
	 * @return unopened participant; call start after network routes are ready
	 */
	public static Future<VertxLeaderNode> fromStore(AsyncRaftStore store, RaftElection.Timing timing,
		RandomGenerator random, RaftTransport transport) {
		return ContextDispatch.call(store.context(), () -> store.open().compose(v -> store.bootstrapState()).map(binding -> {
			if (!binding.installed()) throw new IllegalStateException("Bootstrap installation is required before participation");
			return new VertxLeaderNode(store, binding.genesis().clusterId(), binding.genesis().voters(), timing, random, transport);
		}));
	}

	/**
	 * Identifies this node's execution context.
	 * @return owning event-loop context
	 */
	public Context context() { return context; }

	/**
	 * Registers a readiness observer; registration and callbacks run on the owning context.
	 * @param handler observer, or null to unregister; receives the current observation on registration
	 * @return this node
	 */
	public VertxLeaderNode leadershipHandler(Handler<Leadership> handler) {
		context.runOnContext(v -> {
			leadershipHandler = handler;
			if (handler != null) notifyHandler(handler, leadership);
		});
		return this;
	}

	/**
	 * Registers an error observer. Transport and subscriber failures are reported without stopping Raft;
	 * persistence failures stop participation and release storage after pending I/O finishes.
	 * @param handler observer, or null to unregister
	 * @return this node
	 */
	public VertxLeaderNode exceptionHandler(Handler<Throwable> handler) {
		context.runOnContext(v -> exceptionHandler = handler);
		return this;
	}

	/**
	 * Registers retained committed-entry delivery, including no-ops and recovery replay after the checkpoint.
	 * Delivery is at least once across restarts; consumers must deduplicate by index.
	 * @param handler observer, or null to unregister
	 * @return this node
	 */
	public VertxLeaderNode committedHandler(Handler<RaftLogEntry> handler) {
		context.runOnContext(v -> committedHandler = handler);
		return this;
	}

	/**
	 * Installs application checkpoint hooks before startup. Both execute on the owning context and must be nonblocking.
	 * The provider must capture exactly the requested committed index, even if later entries arrive while it runs.
	 * Restoration must be idempotent; recovery can repeat it after a crash. Empty metadata checkpoints need no hooks.
	 * @param provider asynchronous UTF-8 checkpoint producer, bounded by {@link RaftSnapshot#MAX_DATA_BYTES}
	 * @param restorer asynchronous application restoration, completed before replay or snapshot acknowledgment
	 * @return registration completion; fails once startup has begun
	 */
	public Future<Void> snapshotHandlers(Function<RaftElection.SnapshotPoint, Future<String>> provider,
		Function<RaftSnapshot, Future<Void>> restorer) {
		return ContextDispatch.call(context, () -> {
			if (status != Status.NEW) throw new IllegalStateException("Register snapshot hooks before startup");
			snapshotProvider = Objects.requireNonNull(provider, "provider");
			snapshotRestorer = Objects.requireNonNull(restorer, "restorer");
			return Future.succeededFuture();
		});
	}

	/**
	 * Captures and durably compacts a committed prefix. DATA entries require registered application snapshot hooks.
	 * Protocol progress continues during checkpoint preparation; later entries are retained.
	 * @return the durable checkpoint; failure preserves the previous durable state unless storage itself fails
	 */
	public Future<RaftSnapshot> compact() {
		return ContextDispatch.call(context, () -> { requireRunning(); return beginCompaction(); });
	}

	/**
	 * Starts participation after durable recovery.
	 * @return completion of recovery and participation startup, not leader readiness
	 */
	public Future<Void> start() {
		return ContextDispatch.call(context, () -> {
			if (starting != null && (status == Status.STARTING || status == Status.RUNNING)) return starting.future();
			if (status != Status.NEW) throw new IllegalStateException("Create a new node after close or failure");
			status = Status.STARTING;
			starting = ContextDispatch.promise(context);
			ContextDispatch.relay(context, store.open()).compose(v -> {
				if (status != Status.STARTING) return Future.failedFuture(new CancellationException("Startup canceled"));
				return ContextDispatch.relay(context, store.identity());
			}).compose(recovered -> {
				identity = recovered;
				return bindCluster().compose(v -> ContextDispatch.relay(context, store.persistentState()));
			}).compose(recovered -> {
				if (status != Status.STARTING) return Future.failedFuture(new CancellationException("Startup canceled"));
				return restore(recovered.snapshot()).map(recovered);
			}).onComplete(result -> context.runOnContext(v -> {
				if (status != Status.STARTING) return;
				if (result.failed()) { fail(result.cause()); return; }
				try {
					engine = new RaftElection(identity, clusterId, voters, result.result(), timing, random, learnerAdmission);
					status = Status.RUNNING;
					healthTimer = context.owner().setPeriodic(timing.heartbeatMillis(), id -> {
						if (status == Status.RUNNING) { updateLeadership(); scheduleCompaction(); }
					});
					apply(engine.start());
					starting.tryComplete();
				} catch (Exception e) { fail(e); }
			}));
			return starting.future();
		});
	}

	/**
	 * Accepts an authenticated, addressed protocol message. This future confirms dispatch only.
	 * @param envelope transport-validated envelope
	 * @return dispatch completion
	 */
	public Future<Void> receive(RaftElection.Envelope envelope) {
		return ContextDispatch.call(context, () -> {
			requireRunning();
			Objects.requireNonNull(envelope, "envelope");
			if (validPeer(envelope)) {
				observedTerm = Math.max(observedTerm, envelope.message().term());
			}
			if (identity.groupName().equals(envelope.groupName()) && clusterId.equals(envelope.clusterId())
				&& identity.nodeId().equals(envelope.to()) && !identity.nodeId().equals(envelope.from())) acknowledge(envelope);
			apply(engine.onMessage(envelope));
			return Future.succeededFuture();
		});
	}

	/**
	 * Reads a fencing epoch only while this node is currently ready to lead application work.
	 * The destination must enforce the token atomically; previously issued tokens cannot be revoked locally.
	 * @return coherent group, persisted cluster identity and ready leadership term
	 */
	public Future<LeadershipToken> fencingToken() {
		return ContextDispatch.call(context, () -> {
			requireRunning(); updateLeadership();
			if (!leadership.leader()) return Future.failedFuture(new RaftUnavailableException("Ready leadership is required for a fencing token"));
			return Future.succeededFuture(new LeadershipToken(identity.groupName(), clusterId, leadership.term()));
		});
	}

	/**
	 * Proposes one bounded coordination entry. Only one local proposal may be outstanding.
	 * A failed future after leadership loss does not prove the entry was never committed.
	 * @param data opaque coordination payload
	 * @return entry after local durable commitment
	 */
	public Future<RaftLogEntry> propose(String data) {
		return ContextDispatch.call(context, () -> {
			requireRunning();
			updateLeadership();
			if (!leadership.leader() || proposal != null || membershipChange != null || engine.snapshot().persistencePending()) {
				throw new RaftUnavailableException("A ready leader with no outstanding write is required");
			}
			List<RaftElection.Effect> effects = engine.propose(data);
			var log = engine.replication().state().log();
			proposedEntry = log.getLast();
			proposal = ContextDispatch.promise(context);
			Future<RaftLogEntry> result = proposal.future();
			apply(effects);
			return result;
		});
	}

	/**
	 * Adds an identity already admitted to this cluster as a replication-only learner.
	 * @param learner authenticated admitted identity
	 * @return committed learner configuration (catch-up may still be in progress)
	 */
	public Future<RaftConfiguration> addLearner(String learner) {
		return changeMembership(() -> engine.addLearner(learner));
	}

	/**
	 * Removes a replication-only learner.
	 * @param learner existing learner identity
	 * @return committed configuration
	 */
	public Future<RaftConfiguration> removeLearner(String learner) {
		return changeMembership(() -> engine.removeLearner(learner));
	}

	/**
	 * Changes voting membership through durable joint consensus and finalization.
	 * New voters must first catch up as learners. Failure can leave an outcome to recover from the log.
	 * @param desired desired nonempty voter set
	 * @return final committed stable configuration, including when the local leader removes itself
	 */
	public Future<RaftConfiguration> changeVoters(Set<String> desired) {
		var copy = Set.copyOf(desired);
		return changeMembership(() -> engine.changeVoters(copy));
	}

	/**
	 * Reads membership derived from the latest and durably committed log.
	 * @return latest and committed membership, including unfinished transitions
	 */
	public Future<RaftElection.MembershipSnapshot> membership() {
		return ContextDispatch.call(context, () -> { requireRunning(); return Future.succeededFuture(engine.membership()); });
	}

	/**
	 * Reads voter and learner replication progress.
	 * @return replication diagnostics used to check learner catch-up
	 */
	public Future<RaftElection.ReplicationSnapshot> replication() {
		return ContextDispatch.call(context, () -> { requireRunning(); return Future.succeededFuture(engine.replication()); });
	}

	/**
	 * Reads readiness, configuration, current-term replication health and operation state atomically.
	 * @return one immutable observation on the owner context
	 */
	public Future<MembershipView> membershipView() { return membershipView(false); }

	Future<MembershipView> controlMembershipView() { return membershipView(true); }

	private Future<MembershipView> membershipView(boolean control) {
		return ContextDispatch.call(context, () -> {
			requireRunning(); updateLeadership();
			var replicated = engine.replication();
			var fresh = new java.util.HashSet<String>(); fresh.add(identity.nodeId());
			long now = System.nanoTime(); long window = TimeUnit.MILLISECONDS.toNanos(timing.electionMinMillis());
			acknowledgements.forEach((member, at) -> { if (now - at < window) fresh.add(member); });
			var observation = control ? new Leadership(protocolReady, leadership.term(), leadership.leaderId()) : leadership;
			return Future.succeededFuture(new MembershipView(observation, engine.membership(), replicated.matchIndex(),
				replicated.durableState().commitIndex(), fresh,
				engine.snapshot().persistencePending() || proposal != null || membershipChange != null));
		});
	}

	Future<RaftConfiguration> addLearner(String id, long term, long configurationIndex) {
		return changeMembership(() -> engine.addLearner(id), term, configurationIndex);
	}

	Future<RaftConfiguration> removeLearner(String id, long term, long configurationIndex) {
		return changeMembership(() -> engine.removeLearner(id), term, configurationIndex);
	}

	Future<RaftConfiguration> changeVoters(Set<String> desired, long term, long configurationIndex) {
		var copy = Set.copyOf(desired);
		return changeMembership(() -> engine.changeVoters(copy), term, configurationIndex);
	}

	private Future<RaftConfiguration> changeMembership(java.util.function.Supplier<List<RaftElection.Effect>> operation) {
		return changeMembership(operation, -1, -1);
	}

	private Future<RaftConfiguration> changeMembership(java.util.function.Supplier<List<RaftElection.Effect>> operation,
		long expectedTerm, long expectedConfigurationIndex) {
		return ContextDispatch.call(context, () -> {
			requireRunning(); updateLeadership();
			if (!(expectedTerm >= 0 ? protocolReady : leadership.leader()) || proposal != null || membershipChange != null
				|| (expectedTerm >= 0 && (leadership.term() != expectedTerm
					|| engine.membership().configurationIndex() != expectedConfigurationIndex))) {
				return Future.failedFuture(new RaftUnavailableException("A ready leader with no outstanding operation is required"));
			}
			var effects = operation.get();
			var configuration = engine.membership().effective();
			expectedMembership = RaftConfiguration.stable(configuration.newVoters(), configuration.learners());
			membershipTerm = engine.snapshot().hardState().currentTerm();
			membershipChange = ContextDispatch.promise(context);
			var result = membershipChange.future();
			apply(effects);
			return result;
		});
	}

	/**
	 * Reads immutable protocol diagnostics.
	 * @return current protocol diagnostics, on the owning context
	 */
	public Future<RaftElection.Snapshot> snapshot() {
		return ContextDispatch.call(context, () -> {
			requireRunning();
			return Future.succeededFuture(engine.snapshot());
		});
	}

	/**
	 * Refreshes and reads local leader readiness.
	 * @return current readiness observation, on the owning context
	 */
	public Future<Leadership> leadership() {
		return ContextDispatch.call(context, () -> {
			if (status == Status.RUNNING) { updateLeadership(); scheduleCompaction(); }
			return Future.succeededFuture(leadership);
		});
	}

	/**
	 * Permanently suspends application leadership for this instance while keeping Raft available for removal.
	 * New application proposals are rejected. Already accepted proposals may still commit.
	 * @return completion after local demotion; create a new node to resume application participation
	 */
	public Future<Void> drain() {
		return ContextDispatch.call(context, () -> {
			requireRunning(); draining = true; updateLeadership(); return Future.succeededFuture();
		});
	}

	/**
	 * Stops participation and releases the store after accepted I/O finishes.
	 * @return completion after timer cancellation, pending I/O and ownership release
	 */
	public Future<Void> close() {
		return ContextDispatch.call(context, () -> {
			if (closeFuture != null) return closeFuture;
			status = Status.CLOSING;
			if (starting != null) starting.tryFail(new CancellationException("Node closed during startup"));
			stopParticipation();
			closeFuture = releaseStore().andThen(result -> status = Status.CLOSED);
			return closeFuture;
		});
	}

	private void apply(List<RaftElection.Effect> effects) {
		if (status != Status.RUNNING) return;
		// Complete effect dispatch before any asynchronous completion can reenter the core.
		for (var effect : effects) {
			if (effect instanceof RaftElection.WriteState write) {
				Future<Void> saved = ContextDispatch.relay(context, store.savePersistentState(write.persistentState())).compose(v -> {
					if (status != Status.RUNNING || !write.restoreSnapshot()) return Future.succeededFuture();
					return restore(write.persistentState().snapshot());
				});
				applicationBarrier = saved;
				saved.onComplete(result -> context.runOnContext(v -> {
					if (status != Status.RUNNING) return;
					apply(result.succeeded() ? engine.onPersistenceSucceeded(write)
						: engine.onPersistenceFailed(write, new IOException("Raft persistence or restoration failed", result.cause())));
					if (result.succeeded() && write == compactionWrite && compaction != null) {
						var completed = compaction; var checkpoint = preparedSnapshot;
						clearCompaction(); completed.tryComplete(checkpoint);
					}
				}));
			} else if (effect instanceof RaftElection.ScheduleTimer timer) {
				long id = context.owner().setTimer(timer.delayMillis(), ignored -> {
					timers.remove(timer);
					if (status == Status.RUNNING) apply(engine.onTimeout(timer));
				});
				timers.put(timer, id);
			} else if (effect instanceof RaftElection.CancelTimer cancel) {
				Long id = timers.remove(cancel.timer());
				if (id != null) context.owner().cancelTimer(id);
			} else if (effect instanceof RaftElection.Send send) {
				send(send.envelope());
			} else if (effect instanceof RaftElection.Committed committed) {
				RaftLogEntry entry = committed.entry();
				if (membershipChange != null && entry.kind() == RaftLogEntry.Kind.CONFIGURATION
					&& RaftConfiguration.decode(entry.data()).equals(expectedMembership)) {
					var completed = membershipChange; var result = expectedMembership;
					membershipChange = null; expectedMembership = null;
					context.runOnContext(v -> completed.tryComplete(result));
				}
				if (entry.equals(proposedEntry)) {
					Promise<RaftLogEntry> completed = proposal;
					proposal = null;
					proposedEntry = null;
					context.runOnContext(v -> completed.tryComplete(entry));
				}
				if (committedHandler != null) {
					Handler<RaftLogEntry> handler = committedHandler;
					context.runOnContext(v -> { if (status == Status.RUNNING) notifyHandler(handler, entry); });
				}
			} else if (effect instanceof RaftElection.Failure failed) {
				fail(failed.cause());
				return;
			}
		}
		updateLeadership();
		scheduleCompaction();
	}

	private void send(RaftElection.Envelope envelope) {
		if (envelope.message() instanceof RaftElection.AppendEntries
			|| envelope.message() instanceof RaftElection.InstallSnapshot) {
			trackTerm();
			requests.put(envelope.to(), new Sent(envelope.message(), System.nanoTime()));
		}
		try {
			Objects.requireNonNull(transport.send(envelope), "transport future").onFailure(cause ->
				context.runOnContext(v -> { if (status == Status.RUNNING) report(cause); }));
		} catch (Exception e) { report(e); }
	}

	private boolean validPeer(RaftElection.Envelope envelope) {
		return identity.groupName().equals(envelope.groupName()) && clusterId.equals(envelope.clusterId())
			&& identity.nodeId().equals(envelope.to()) && !identity.nodeId().equals(envelope.from())
			&& engine.observesTerm(envelope);
	}

	private void trackTerm() {
		long term = engine.snapshot().hardState().currentTerm();
		var configuration = engine.membership().effective();
		if (trackedTerm != term || trackedConfiguration == null
			|| !trackedConfiguration.oldVoters().equals(configuration.oldVoters())
			|| !trackedConfiguration.newVoters().equals(configuration.newVoters())) {
			trackedTerm = term;
			trackedConfiguration = configuration;
			requests.clear();
			acknowledgements.clear();
		}
	}

	private void acknowledge(RaftElection.Envelope envelope) {
		trackTerm();
		Sent sent = requests.get(envelope.from());
		if (sent == null || envelope.message().term() != trackedTerm || sent.request().term() != trackedTerm) return;
		boolean accepted;
		if (envelope.message() instanceof RaftElection.AppendResponse response
			&& sent.request() instanceof RaftElection.AppendEntries request && response.requestId() == request.requestId()) {
			accepted = response.success() && response.matchIndex() == request.previous().index() + request.entries().size();
		} else if (envelope.message() instanceof RaftElection.SnapshotResponse response
			&& sent.request() instanceof RaftElection.InstallSnapshot request && response.requestId() == request.requestId()) {
			accepted = response.accepted() && response.lastIncludedIndex() == request.snapshot().lastIncludedIndex();
		} else return;
		requests.remove(envelope.from());
		// Anchor freshness to dispatch: delayed replies cannot extend quorum evidence indefinitely.
		if (accepted) acknowledgements.put(envelope.from(), sent.sentAt());
	}

	private void updateLeadership() {
		boolean ready = false;
		long term = leadership.term();
		String leaderId = null;
		if (engine != null) {
			var snapshot = engine.snapshot();
			term = snapshot.hardState().currentTerm();
			leaderId = snapshot.leaderId();
			trackTerm();
			if (status == Status.RUNNING && snapshot.role() == RaftElection.Role.LEADER && term >= observedTerm) {
				var durable = engine.replication().durableState();
				long now = System.nanoTime();
				long window = TimeUnit.MILLISECONDS.toNanos(timing.electionMinMillis());
				var configuration = engine.membership().effective();
				Set<String> fresh = new java.util.HashSet<>(); fresh.add(identity.nodeId());
				acknowledgements.forEach((member, at) -> { if (now - at < window) fresh.add(member); });
				ready = durable.commitIndex() > 0
					&& durable.termAt(durable.commitIndex()) == term
					&& configuration.voters().contains(identity.nodeId()) && configuration.hasQuorum(fresh);
			}
		}
		if (membershipChange != null && (status != Status.RUNNING || engine.snapshot().role() != RaftElection.Role.LEADER
			|| engine.snapshot().hardState().currentTerm() != membershipTerm)) {
			membershipChange.tryFail(new IllegalStateException("Membership outcome may be unknown after leadership loss"));
			membershipChange = null; expectedMembership = null;
		}
		protocolReady = ready;
		ready = ready && !draining;
		Leadership next = new Leadership(ready, term, leaderId);
		if (!ready) failProposal(new IllegalStateException("Leadership unavailable; proposal outcome may be unknown"));
		if (!next.equals(leadership)) {
			leadership = next;
			Handler<Leadership> handler = leadershipHandler;
			if (handler != null) {
				context.runOnContext(v -> {
					// Suppress obsolete positive notifications after a concurrent shutdown or demotion.
					if (!next.leader() || (status == Status.RUNNING && next.equals(leadership))) notifyHandler(handler, next);
				});
			}
		}
	}

	private void failProposal(Throwable cause) {
		if (proposal != null) {
			proposal.tryFail(cause);
			proposal = null;
			proposedEntry = null;
		}
	}

	private void stopParticipation() {
		if (compaction != null) compaction.tryFail(new CancellationException("Node stopped during compaction"));
		clearCompaction();
		if (healthTimer != -1) context.owner().cancelTimer(healthTimer);
		timers.values().forEach(context.owner()::cancelTimer);
		timers.clear();
		requests.clear();
		acknowledgements.clear();
		if (engine != null) engine.stop();
		updateLeadership();
	}

	private void fail(Throwable cause) {
		status = Status.FAILED;
		if (starting != null) starting.tryFail(cause);
		stopParticipation();
		report(cause);
		closeFuture = releaseStore();
		closeFuture.onFailure(this::report);
	}

	private Future<Void> restore(RaftSnapshot checkpoint) {
		if (checkpoint == null) return Future.succeededFuture();
		try {
			applicationBarrier = ContextDispatch.relay(context, Objects.requireNonNull(snapshotRestorer.apply(checkpoint), "restore future"));
		} catch (Exception e) { applicationBarrier = Future.failedFuture(e); }
		return applicationBarrier;
	}

	private Future<Void> releaseStore() {
		return applicationBarrier.recover(cause -> Future.succeededFuture()).compose(v -> ContextDispatch.relay(context, store.close()));
	}

	private Future<RaftSnapshot> beginCompaction() {
		if (compaction != null) return Future.failedFuture(new RaftUnavailableException("A checkpoint is already in progress"));
		if (engine.snapshot().persistencePending()) return Future.failedFuture(new RaftUnavailableException("Wait for the pending durable write"));
		var point = engine.snapshotPoint();
		if (point.containsData() && snapshotProvider == null) return Future.failedFuture(new IllegalStateException("DATA requires application snapshot hooks"));
		compaction = ContextDispatch.promise(context);
		var pending = compaction;
		var result = pending.future();
		try {
			Future<String> data = point.containsData() ? Objects.requireNonNull(snapshotProvider.apply(point), "snapshot future")
				: Future.succeededFuture(point.previousData());
			ContextDispatch.relay(context, data).onComplete(outcome -> {
				if (status != Status.RUNNING || compaction != pending) return;
				try {
					if (outcome.failed()) throw new IllegalStateException("Application checkpoint failed", outcome.cause());
					preparedSnapshot = new RaftSnapshot(point.index(), point.term(), point.configuration(),
						point.configurationIndex(), outcome.result());
					scheduleCompaction();
				} catch (Exception e) { clearCompaction(); pending.tryFail(e); }
			});
		} catch (Exception e) { clearCompaction(); pending.tryFail(e); }
		return result;
	}

	private void clearCompaction() { compaction = null; preparedSnapshot = null; compactionWrite = null; }

	private void scheduleCompaction() {
		if (compactionScheduled || status != Status.RUNNING) return;
		compactionScheduled = true;
		// Committed-entry handlers already queued by this effect batch run before checkpoint preparation.
		context.runOnContext(v -> {
			compactionScheduled = false;
			if (status != Status.RUNNING || engine.snapshot().persistencePending()) return;
			if (preparedSnapshot != null && compactionWrite == null) {
				try {
					var effects = engine.compact(preparedSnapshot);
					compactionWrite = effects.stream().filter(RaftElection.WriteState.class::isInstance)
						.map(RaftElection.WriteState.class::cast).findFirst().orElseThrow();
					apply(effects);
				} catch (Exception e) { var pending = compaction; clearCompaction(); pending.tryFail(e); }
			} else if (compaction == null && System.nanoTime() >= nextAutomaticCompaction) {
				var state = engine.replication().durableState();
				if (state.commitIndex() - state.baseIndex() < COMPACTION_THRESHOLD) return;
				var point = engine.snapshotPoint();
				if (point.containsData() && snapshotProvider == null) return;
				nextAutomaticCompaction = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
				beginCompaction().onFailure(this::report);
			}
		});
	}

	private Future<Void> bindCluster() {
		return ContextDispatch.relay(context, store.bootstrapState()).compose(binding -> {
			if (binding.genesis() == null) {
				// Explicit fixed-set construction pins the caller's agreed configuration exactly once.
				var genesis = RaftGenesis.create(clusterId, identity.groupName(), null, voters, voters.size());
				return ContextDispatch.relay(context, transport.validateBinding(identity, genesis)).compose(v -> ContextDispatch.relay(context, store.installGenesis(genesis)));
			}
			if (!binding.installed() || !clusterId.equals(binding.genesis().clusterId())
				|| !voters.equals(binding.genesis().voters())) {
				throw new IllegalStateException("Configured cluster/voters do not match installed genesis");
			}
			learnerAdmission = binding.learnerAdmission();
			return ContextDispatch.relay(context, transport.validateBinding(identity, binding.genesis(), learnerAdmission));
		});
	}

	private void requireRunning() {
		if (status != Status.RUNNING) throw new IllegalStateException("Node is not running: " + status);
	}

	private <T> void notifyHandler(Handler<T> handler, T event) {
		try { handler.handle(event); } catch (Exception e) { report(e); }
	}

	private void report(Throwable cause) {
		if (exceptionHandler == null) { LOG.warn("Raft adapter operation failed", cause); return; }
		try { exceptionHandler.handle(cause); } catch (Exception e) { LOG.warn("Raft exception handler failed", e); }
	}
}
