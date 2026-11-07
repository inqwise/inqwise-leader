package com.inqwise.leader;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.random.RandomGenerator;

/**
 * Deterministic election, replication and joint-membership core. Call from one serialized event processor.
 * Returned effects are instructions to an adapter, not callbacks executed by this class.
 * Finish consuming each effect batch before delivering subsequent events, including persistence completions.
 * Protocol leadership is not permission to run application jobs: wall-clock quorum checks, application checkpoints,
 * and application readiness belong to adapters.
 */
public final class RaftElection {
	private static final int MAX_PENDING_EVENTS = 1024;
	/** Maximum entries in a single AppendEntries request. */
	public static final int MAX_APPEND_ENTRIES = 32;

	/** Protocol role, including terminal lifecycle states. */
	public enum Role {
		/** Waiting for an election or a leader's heartbeat. */
		FOLLOWER,
		/** Requesting votes in the current term. */
		CANDIDATE,
		/** Elected by a majority; does not imply application readiness. */
		LEADER,
		/** Not started or explicitly stopped. */
		STOPPED,
		/** Participation ended after an unrecoverable error. */
		FAILED
	}
	/** Kind of external timer requested by the engine. */
	public enum TimerKind {
		/** Follower/candidate election deadline. */
		ELECTION,
		/** Leader heartbeat deadline. */
		HEARTBEAT,
		/** Minimum leader-contact window protecting against disruptive vote requests. */
		LEADER_CONTACT
	}

	/**
	 * Log position used for consistency checks and election freshness comparisons.
	 * @param index last entry index, zero for an empty log
	 * @param term last entry term, zero for an empty log
	 */
	public record LogPosition(long index, long term) {
		/**
		 * Validates a recovered or advertised log position.
		 * @param index last entry index
		 * @param term last entry term
		 */
		public LogPosition {
			if (index < 0 || term < 0 || ((index == 0) != (term == 0))) {
				throw new IllegalArgumentException("Log index/term must both be zero or both be positive");
			}
		}
	}

	/**
	 * Election timeout bounds use an inclusive minimum and exclusive maximum, in milliseconds.
	 * @param electionMinMillis minimum election timeout
	 * @param electionMaxMillis exclusive maximum election timeout
	 * @param heartbeatMillis interval between leader heartbeats
	 */
	public record Timing(long electionMinMillis, long electionMaxMillis, long heartbeatMillis) {
		/**
		 * Validates that heartbeat timing is shorter than the randomized election range.
		 * @param electionMinMillis minimum election timeout
		 * @param electionMaxMillis exclusive maximum election timeout
		 * @param heartbeatMillis heartbeat interval
		 */
		public Timing {
			if (heartbeatMillis <= 0 || electionMinMillis <= heartbeatMillis
				|| electionMaxMillis <= electionMinMillis) {
				throw new IllegalArgumentException("Require 0 < heartbeat < election minimum < election maximum");
			}
		}

		/**
		 * Returns default election and heartbeat timing.
		 * @return election range of 2500-5000 ms and heartbeat interval of 1000 ms
		 */
		public static Timing defaults() {
			return new Timing(2500, 5000, 1000);
		}
	}

	/**
	 * In-memory protocol state and the last confirmed durable state.
	 * @param role current protocol role
	 * @param hardState proposed/current in-memory term and vote
	 * @param durableState last confirmed durable term and vote
	 * @param leaderId known leader, or {@code null}
	 * @param votes unique voters supporting the current candidate/leader
	 * @param persistencePending whether protocol processing is waiting for storage
	 */
	public record Snapshot(Role role, RaftHardState hardState, RaftHardState durableState,
		String leaderId, Set<String> votes, boolean persistencePending) {
		/**
		 * Copies votes so snapshots cannot expose mutable election state.
		 * @param role current protocol role
		 * @param hardState current in-memory state
		 * @param durableState last confirmed durable state
		 * @param leaderId known leader, or {@code null}
		 * @param votes supporting voters
		 * @param persistencePending whether storage is pending
		 */
		public Snapshot {
			votes = Set.copyOf(votes);
		}
	}

	/** Protocol messages, including empty AppendEntries requests as heartbeats. */
	public sealed interface Message permits VoteRequest, VoteResponse, AppendEntries, AppendResponse, InstallSnapshot, SnapshotResponse {
		/**
		 * Identifies the sender's current term.
		 * @return sender's protocol term
		 */
		long term();
	}

	/**
	 * Request a vote in a positive election term.
	 * @param term candidate's current term
	 * @param lastLog candidate's last durable log position
	 */
	public record VoteRequest(long term, LogPosition lastLog) implements Message {
		/**
		 * Validates the term and log position.
		 * @param term election term
		 * @param lastLog candidate's log position
		 */
		public VoteRequest {
			requireTerm(term, true);
			Objects.requireNonNull(lastLog, "lastLog");
			if (lastLog.term() > term) {
				throw new IllegalArgumentException("Candidate log term cannot exceed the election term");
			}
		}
	}

	/**
	 * Election term correlates the response with the original vote request.
	 * @param term responding voter's current term
	 * @param electionTerm original vote request term
	 * @param granted whether this voter granted its vote
	 */
	public record VoteResponse(long term, long electionTerm, boolean granted) implements Message {
		/**
		 * Validates the current and original request terms.
		 * @param term current term
		 * @param electionTerm original request term
		 * @param granted vote result
		 */
		public VoteResponse {
			requireTerm(term, false);
			requireTerm(electionTerm, true);
		}
	}

	/**
	 * Bounded replication request; an empty entries list is a consistency-checked heartbeat.
	 * @param term leader term
	 * @param requestId correlation within the leader term
	 * @param previous preceding log position
	 * @param entries contiguous entries after previous
	 * @param leaderCommit leader's committed prefix length
	 */
	public record AppendEntries(long term, long requestId, LogPosition previous,
		List<RaftLogEntry> entries, long leaderCommit) implements Message {
		/**
		 * Validates request bounds and entry continuity.
		 * @param term leader term
		 * @param requestId request correlation
		 * @param previous preceding log position
		 * @param entries entries to replicate
		 * @param leaderCommit committed prefix length
		 */
		public AppendEntries {
			requireTerm(term, true);
			Objects.requireNonNull(previous, "previous");
			entries = List.copyOf(entries);
			if (requestId <= 0 || leaderCommit < 0 || entries.size() > MAX_APPEND_ENTRIES || previous.term() > term) {
				throw new IllegalArgumentException("Invalid AppendEntries bounds");
			}
			long index = previous.index();
			long entryTerm = previous.term();
			for (RaftLogEntry entry : entries) {
				if (index == Long.MAX_VALUE || entry.index() != index + 1
					|| entry.term() < entryTerm || entry.term() > term) {
					throw new IllegalArgumentException("AppendEntries must carry contiguous entries with ordered terms");
				}
				index = entry.index();
				entryTerm = entry.term();
			}
		}
	}

	/**
	 * Correlated replication response. Successful matchIndex covers only the original request's proven prefix.
	 * @param term responder term
	 * @param requestId original request correlation
	 * @param success consistency and durable write result
	 * @param matchIndex acknowledged last covered index
	 * @param nextIndexHint suggested retry index on rejection
	 */
	public record AppendResponse(long term, long requestId, boolean success,
		long matchIndex, long nextIndexHint) implements Message {
		/**
		 * Validates response bounds.
		 * @param term responder term
		 * @param requestId correlation
		 * @param success write/consistency result
		 * @param matchIndex proven match
		 * @param nextIndexHint retry hint
		 */
		public AppendResponse {
			requireTerm(term, false);
			if (requestId <= 0 || matchIndex < 0 || nextIndexHint < 1) {
				throw new IllegalArgumentException("Invalid AppendEntries response bounds");
			}
		}
	}

	/**
	 * Bounded full-snapshot transfer; superseded requests are correlated by term and request ID.
	 * @param term leader term
	 * @param requestId request correlation
	 * @param snapshot committed checkpoint
	 */
	public record InstallSnapshot(long term, long requestId, RaftSnapshot snapshot) implements Message {
		/**
		 * Validates transfer metadata.
		 * @param term leader term
		 * @param requestId correlation
		 * @param snapshot checkpoint
		 */
		public InstallSnapshot {
			requireTerm(term, true); Objects.requireNonNull(snapshot, "snapshot");
			if (requestId <= 0 || snapshot.lastIncludedTerm() > term) throw new IllegalArgumentException("Invalid snapshot transfer");
		}
	}

	/**
	 * Confirms durable installation and application restoration before granting prefix progress.
	 * @param term responder term
	 * @param requestId original request
	 * @param accepted whether installation/prefix confirmation succeeded
	 * @param lastIncludedIndex exact requested checkpoint boundary
	 */
	public record SnapshotResponse(long term, long requestId, boolean accepted, long lastIncludedIndex) implements Message {
		/**
		 * Validates response metadata.
		 * @param term responder term
		 * @param requestId correlation
		 * @param accepted installation result
		 * @param lastIncludedIndex boundary
		 */
		public SnapshotResponse {
			requireTerm(term, false);
			if (requestId <= 0 || lastIncludedIndex <= 0) throw new IllegalArgumentException("Invalid snapshot response");
		}
	}

	/**
	 * Authenticated transport must preserve and validate the sender/receiver bindings.
	 * @param groupName consensus group
	 * @param clusterId agreed cluster identity
	 * @param from authenticated, cluster-admitted peer identity
	 * @param to intended receiver identity
	 * @param message protocol payload
	 */
	public record Envelope(String groupName, String clusterId, String from, String to, Message message) {
		/**
		 * Validates required fields and node ID syntax.
		 * @param groupName consensus group
		 * @param clusterId cluster identity
		 * @param from sender identity
		 * @param to receiver identity
		 * @param message payload
		 */
		public Envelope {
			Objects.requireNonNull(groupName, "groupName");
			Objects.requireNonNull(clusterId, "clusterId");
			RaftIdentity.validateNodeId(from);
			RaftIdentity.validateNodeId(to);
			Objects.requireNonNull(message, "message");
		}
	}

	/** Instructions for storage, transport, timers, or protocol-state observation. */
	public sealed interface Effect
		permits WriteState, Send, ScheduleTimer, CancelTimer, RoleChanged, Failure, Committed {}
	/**
	 * Retain this exact object when reporting success/failure; reconstructed or obsolete tokens are ignored.
	 * @param sequence diagnostic sequence number within an engine instance
	 * @param persistentState complete state that must become durable before completion is reported
	 * @param restoreSnapshot whether the adapter must also finish application restoration before confirming this write
	 */
	public record WriteState(long sequence, RaftPersistentState persistentState, boolean restoreSnapshot) implements Effect {
		/**
		 * Creates a normal durable write without application restoration.
		 * @param sequence write token
		 * @param persistentState complete state
		 */
		public WriteState(long sequence, RaftPersistentState persistentState) { this(sequence, persistentState, false); }
		/**
		 * Reads term/vote diagnostics; persist the complete persistentState instead of only this value.
		 * @return term and vote within the requested atomic write
		 */
		public RaftHardState state() {
			return persistentState.hardState();
		}
	}

	/**
	 * Emits a durably committed entry in index order; committed entries are replayed on restart.
	 * Adapters must apply idempotently or maintain their own atomic application/snapshot boundary.
	 * @param entry committed coordination entry
	 */
	public record Committed(RaftLogEntry entry) implements Effect {}

	/**
	 * Diagnostic replication state; proposed state may be ahead of confirmed durable state while writing.
	 * @param state proposed/current state
	 * @param durableState confirmed durable state
	 * @param lastEmittedIndex last committed index emitted to the adapter
	 * @param nextIndex per-follower retry positions
	 * @param matchIndex confirmed replication positions, including self
	 */
	public record ReplicationSnapshot(RaftPersistentState state, RaftPersistentState durableState,
		long lastEmittedIndex, Map<String, Long> nextIndex, Map<String, Long> matchIndex) {
		/**
		 * Copies diagnostic maps.
		 * @param state proposed state
		 * @param durableState durable state
		 * @param lastEmittedIndex last emitted committed entry
		 * @param nextIndex retry positions
		 * @param matchIndex proven match positions
		 */
		public ReplicationSnapshot {
			nextIndex = Map.copyOf(nextIndex);
			matchIndex = Map.copyOf(matchIndex);
		}
	}
	/**
	 * Send one message to the specified peer.
	 * @param envelope addressed protocol message
	 */
	public record Send(Envelope envelope) implements Effect {}
	/**
	 * Timer completion must return this exact object, so a stale timer cannot affect a restarted engine.
	 * @param kind timer purpose
	 * @param sequence diagnostic sequence number
	 * @param delayMillis delay relative to scheduling
	 */
	public record ScheduleTimer(TimerKind kind, long sequence, long delayMillis) implements Effect {}
	/**
	 * Cancel the external timer associated with the exact scheduling token.
	 * @param timer prior scheduling effect
	 */
	public record CancelTimer(ScheduleTimer timer) implements Effect {}
	/**
	 * Reports protocol role/leader changes, never application readiness.
	 * @param snapshot state at the transition
	 */
	public record RoleChanged(Snapshot snapshot) implements Effect {}
	/**
	 * Reports why participation stopped; recovery requires a new engine and recovered storage.
	 * @param cause terminal failure
	 */
	public record Failure(IOException cause) implements Effect {}

	private sealed interface Event permits Received, Timeout {}
	private record Received(Envelope envelope) implements Event {}
	private record Timeout(ScheduleTimer timer) implements Event {}

	private final RaftIdentity identity;
	private final String clusterId;
	private final Set<String> initialVoters;
	private RaftConfiguration.History membership;
	private RaftConfiguration.History durableMembership;
	private List<RaftLogEntry> log;
	private List<RaftLogEntry> durableLog;
	private RaftSnapshot checkpoint;
	private RaftSnapshot durableCheckpoint;
	private long commitIndex;
	private long durableCommitIndex;
	private long lastEmittedIndex;
	private final Map<String, Long> nextIndex = new HashMap<>();
	private final Map<String, Long> matchIndex = new HashMap<>();
	private final Map<String, AppendEntries> inFlight = new HashMap<>();
	private final Map<String, InstallSnapshot> snapshotInFlight = new HashMap<>();
	private final Timing timing;
	private final RandomGenerator random;
	private final Set<String> votes = new HashSet<>();
	private final ArrayDeque<Event> deferred = new ArrayDeque<>();
	private final List<Effect> effects = new ArrayList<>();
	private RaftHardState hardState;
	private RaftHardState durableState;
	private Role role = Role.STOPPED;
	private String leaderId;
	private boolean started;
	private boolean closed;
	private long sequence;
	private ScheduleTimer electionTimer;
	private ScheduleTimer heartbeatTimer;
	private ScheduleTimer leaderContactTimer;
	private WriteState pendingWrite;
	private Runnable afterPersistence;

	/**
	 * Creates a recovered voter. The caller must supply the same agreed membership and cluster binding on restart.
	 * @param identity persistent local identity
	 * @param clusterId agreed cluster identity
	 * @param voters fixed voter identities, including the local node
	 * @param recoveredState durably recovered term, vote, log, and commit index
	 * @param timing heartbeat and randomized election timing
	 * @param random caller-supplied randomness, seeded for reproducible tests
	 */
	public RaftElection(RaftIdentity identity, String clusterId, Set<String> voters, RaftPersistentState recoveredState,
		Timing timing, RandomGenerator random) {
		this(identity, clusterId, voters, recoveredState, timing, random, false);
	}

	/**
	 * Creates a recovered voter or explicitly admitted learner.
	 * @param identity persistent local identity
	 * @param clusterId permanent cluster binding
	 * @param voters immutable genesis voters
	 * @param recoveredState recovered protocol state
	 * @param timing election timing
	 * @param random election randomness
	 * @param admittedLearner whether local admission permits an identity outside the genesis voter set
	 */
	public RaftElection(RaftIdentity identity, String clusterId, Set<String> voters, RaftPersistentState recoveredState,
		Timing timing, RandomGenerator random, boolean admittedLearner) {
		this.identity = Objects.requireNonNull(identity, "identity");
		this.clusterId = Objects.requireNonNull(clusterId, "clusterId");
		if (clusterId.isBlank()) {
			throw new IllegalArgumentException("clusterId must not be blank");
		}
		var ordered = new TreeSet<String>();
		for (String voter : Objects.requireNonNull(voters, "voters")) {
			RaftIdentity.validateNodeId(voter);
			ordered.add(voter);
		}
		if (ordered.isEmpty() || (!admittedLearner && !ordered.contains(identity.nodeId()))) {
			throw new IllegalArgumentException("Genesis voters must include the local voter unless learner admission is present");
		}
		this.initialVoters = Collections.unmodifiableSet(ordered);
		Objects.requireNonNull(recoveredState, "recoveredState");
		hardState = durableState = recoveredState.hardState();
		log = durableLog = recoveredState.log();
		checkpoint = durableCheckpoint = recoveredState.snapshot();
		lastEmittedIndex = baseIndex();
		commitIndex = durableCommitIndex = recoveredState.commitIndex();
		membership = durableMembership = RaftConfiguration.history(initialVoters, log, commitIndex, checkpoint);
		this.timing = Objects.requireNonNull(timing, "timing");
		this.random = Objects.requireNonNull(random, "random");
	}

	/**
	 * Starts a follower; repeated starts are harmless until stopped or failed.
	 * @return protocol observations, election scheduling, and committed-entry replay, or an empty batch if already started
	 */
	public List<Effect> start() {
		if (closed || role == Role.FAILED) {
			throw new IllegalStateException("Recover a new election engine after stop or failure");
		}
		if (!started) {
			started = true;
			changeRole(Role.FOLLOWER, null);
			resetElectionTimer();
			emitCommitted();
		}
		return drain();
	}

	/**
	 * Returns immutable diagnostic state; a pending term/vote is not yet durable.
	 * @return immutable election snapshot
	 */
	public Snapshot snapshot() {
		return new Snapshot(role, hardState, durableState, leaderId, votes, pendingWrite != null);
	}

	/**
	 * Reads immutable log and replication diagnostics.
	 * @return proposed/durable log state and follower progress
	 */
	public ReplicationSnapshot replication() {
		return new ReplicationSnapshot(new RaftPersistentState(hardState, log, commitIndex, checkpoint),
			new RaftPersistentState(durableState, durableLog, durableCommitIndex, durableCheckpoint),
			lastEmittedIndex, nextIndex, matchIndex);
	}

	/**
	 * Captured boundary for an application snapshot; the provider must represent this exact committed position.
	 * @param index committed boundary
	 * @param term boundary term
	 * @param configuration committed membership at boundary
	 * @param configurationIndex membership entry position
	 * @param containsData whether the retained prefix includes DATA entries requiring an application provider
	 * @param previousData prior application checkpoint for unchanged coordination-only state
	 */
	public record SnapshotPoint(long index, long term, RaftConfiguration configuration, long configurationIndex,
		boolean containsData, String previousData) {}

	/**
	 * Captures the current durable committed boundary without stopping protocol progress during preparation.
	 * @return immutable snapshot request
	 */
	public SnapshotPoint snapshotPoint() {
		if (!active() || durableCommitIndex <= baseIndex()) throw new IllegalStateException("No committed prefix to compact");
		var prefix = log.stream().filter(entry -> entry.index() <= durableCommitIndex).toList();
		var at = RaftConfiguration.history(initialVoters, prefix, durableCommitIndex, checkpoint);
		return new SnapshotPoint(durableCommitIndex, position(durableCommitIndex).term(), at.effective(), at.index(),
			prefix.stream().anyMatch(entry -> entry.kind() == RaftLogEntry.Kind.DATA), checkpoint == null ? "" : checkpoint.data());
	}

	/**
	 * Atomically replaces a committed prefix with a checkpoint, retaining later absolute log positions.
	 * @param prepared application checkpoint for an exact committed boundary
	 * @return persistence effects; no prefix is discarded durably before confirmation
	 */
	public List<Effect> compact(RaftSnapshot prepared) {
		Objects.requireNonNull(prepared, "snapshot");
		if (!active() || pendingWrite != null || prepared.lastIncludedIndex() <= baseIndex()
			|| prepared.lastIncludedIndex() > durableCommitIndex
			|| prepared.lastIncludedTerm() != position(prepared.lastIncludedIndex()).term()) {
			throw new IllegalStateException("An idle committed snapshot boundary is required");
		}
		var prefix = log.stream().filter(entry -> entry.index() <= prepared.lastIncludedIndex()).toList();
		var at = RaftConfiguration.history(initialVoters, prefix, prepared.lastIncludedIndex(), checkpoint);
		if (!prepared.configuration().equals(at.effective()) || prepared.configurationIndex() != at.index()) {
			throw new IllegalArgumentException("Snapshot membership differs from committed history");
		}
		var tail = log.subList(Math.toIntExact(prepared.lastIncludedIndex() - baseIndex()), log.size());
		persist(new RaftPersistentState(hardState, tail, commitIndex, prepared), () -> {
			if (role == Role.LEADER) replicateAll(false);
			reconcileMembership();
		});
		return drain();
	}

	/**
	 * Reads latest-log and committed membership; latest-log membership governs decisions immediately.
	 * @return immutable membership diagnostics
	 */
	public MembershipSnapshot membership() {
		return new MembershipSnapshot(membership.effective(), membership.index(), durableMembership.committed(),
			durableMembership.committedIndex());
	}

	/**
	 * Membership positions within the bounded log.
	 * @param effective latest configuration, including an uncommitted transition
	 * @param configurationIndex latest configuration entry, zero for genesis
	 * @param committed last durably committed configuration
	 * @param committedConfigurationIndex committed configuration entry, zero for genesis
	 */
	public record MembershipSnapshot(RaftConfiguration effective, long configurationIndex,
		RaftConfiguration committed, long committedConfigurationIndex) {}

	/**
	 * Adds a previously admitted identity as a non-voting replication target.
	 * @param learner authenticated admitted node ID
	 * @return configuration persistence/replication effects
	 */
	public List<Effect> addLearner(String learner) {
		requireMembershipChange();
		RaftIdentity.validateNodeId(learner);
		var configuration = membership.effective();
		if (configuration.members().contains(learner)) throw new IllegalArgumentException("Member already exists");
		var learners = new TreeSet<>(configuration.learners()); learners.add(learner);
		appendLocal(RaftLogEntry.Kind.CONFIGURATION, RaftConfiguration.stable(configuration.voters(), learners).encode());
		return drain();
	}

	/**
	 * Removes a replication-only learner without altering voting quorums.
	 * @param learner existing learner identity
	 * @return configuration persistence effects
	 */
	public List<Effect> removeLearner(String learner) {
		requireMembershipChange();
		var current = membership.effective();
		var learners = new TreeSet<>(current.learners());
		if (!learners.remove(learner)) throw new IllegalArgumentException("Unknown learner");
		appendLocal(RaftLogEntry.Kind.CONFIGURATION, RaftConfiguration.stable(current.voters(), learners).encode());
		return drain();
	}

	/**
	 * Starts a serialized old/new transition; every added voter must have caught up as a learner.
	 * @param newVoters desired nonempty voter set
	 * @return joint-configuration persistence effects; finalization follows after joint commitment
	 */
	public List<Effect> changeVoters(Set<String> newVoters) {
		requireMembershipChange();
		if (log.size() > RaftPersistentState.MAX_LOG_ENTRIES - 3) {
			throw new IllegalStateException("Insufficient capacity for joint consensus and recovery");
		}
		var current = membership.effective();
		var desired = RaftConfiguration.stable(newVoters, Set.of()).voters();
		if (desired.equals(current.voters())) throw new IllegalArgumentException("Voters are unchanged");
		for (String added : desired) if (!current.voters().contains(added)
			&& (!current.learners().contains(added) || matchIndex.getOrDefault(added, 0L) < durableCommitIndex)) {
			throw new IllegalStateException("New voters must durably catch up as learners first");
		}
		var learners = new TreeSet<>(current.learners()); learners.removeAll(desired);
		appendLocal(RaftLogEntry.Kind.CONFIGURATION,
			new RaftConfiguration(current.voters(), desired, learners).encode());
		return drain();
	}

	private void requireMembershipChange() {
		if (!active() || role != Role.LEADER || pendingWrite != null || membership.effective().joint()
			|| membership.index() > durableCommitIndex || durableCommitIndex == 0
			|| position(durableCommitIndex).term() != hardState.currentTerm()) {
			throw new IllegalStateException("A current-term committed leader and settled configuration are required");
		}
	}

	/**
	 * Appends opaque coordination data on a leader with no pending storage operation.
	 * @param data bounded coordination payload
	 * @return persistence effects; acceptance is not commitment
	 */
	public List<Effect> propose(String data) {
		if (!active() || role != Role.LEADER || pendingWrite != null) {
			throw new IllegalStateException("An active protocol leader with no pending write is required");
		}
		appendLocal(RaftLogEntry.Kind.DATA, data);
		return drain();
	}

	/**
	 * Receives a message from a transport that has already authenticated the sender.
	 * @param envelope addressed message
	 * @return resulting effects; foreign messages are ignored
	 */
	public List<Effect> onMessage(Envelope envelope) {
		Objects.requireNonNull(envelope, "envelope");
		if (identity.groupName().equals(envelope.groupName()) && clusterId.equals(envelope.clusterId())
			&& identity.nodeId().equals(envelope.to()) && !identity.nodeId().equals(envelope.from())
			&& acceptsMessage(envelope)
			&& (!(envelope.message() instanceof VoteRequest) || leaderContactTimer == null)) {
			submit(new Received(envelope));
		}
		return drain();
	}

	/**
	 * Delivers a timer token returned by a previous effect batch.
	 * @param timer original scheduling effect
	 * @return resulting effects; obsolete tokens are ignored
	 */
	public List<Effect> onTimeout(ScheduleTimer timer) {
		Objects.requireNonNull(timer, "timer");
		if (timer == electionTimer || timer == heartbeatTimer || timer == leaderContactTimer) {
			submit(new Timeout(timer));
		}
		return drain();
	}

	/**
	 * Confirms that the exact requested state has become durable.
	 * @param request original write effect
	 * @return released protocol effects and effects from deferred events
	 */
	public List<Effect> onPersistenceSucceeded(WriteState request) {
		if (active() && request != null && request == pendingWrite) {
			durableState = request.state();
			durableLog = request.persistentState().log();
			durableCheckpoint = request.persistentState().snapshot();
			lastEmittedIndex = Math.max(lastEmittedIndex, durableBaseIndex());
			durableCommitIndex = request.persistentState().commitIndex();
			durableMembership = membership;
			Runnable continuation = afterPersistence;
			pendingWrite = null;
			afterPersistence = null;
			continuation.run();
			while (active() && pendingWrite == null && !deferred.isEmpty()) {
				process(deferred.removeFirst());
			}
		}
		return drain();
	}

	/**
	 * Stops participation when a write fails or its durability is uncertain.
	 * @param request original write effect
	 * @param cause storage failure
	 * @return terminal failure and cleanup effects
	 */
	public List<Effect> onPersistenceFailed(WriteState request, IOException cause) {
		Objects.requireNonNull(cause, "cause");
		if (active() && request != null && request == pendingWrite) {
			fail(cause);
		}
		return drain();
	}

	/**
	 * Invalidates pending callbacks; this instance cannot be restarted after stopping.
	 * @return timer cancellation and role change effects, or an empty batch if already stopped
	 */
	public List<Effect> stop() {
		if (!closed) {
			closed = true;
			deferred.clear();
			pendingWrite = null;
			afterPersistence = null;
			cancelTimers();
			votes.clear();
			changeRole(Role.STOPPED, null);
		}
		return drain();
	}

	private boolean active() {
		return started && !closed && role != Role.FAILED;
	}

	private void submit(Event event) {
		if (!active()) {
			return;
		}
		if (pendingWrite == null) {
			process(event);
		} else if (deferred.size() < MAX_PENDING_EVENTS) {
			deferred.addLast(event);
		} else {
			fail(new IOException("Election event queue exhausted while waiting for durable storage"));
		}
	}

	private void process(Event event) {
		switch (event) {
			case Received received -> receive(received.envelope());
			case Timeout timeout -> {
				if (timeout.timer() == electionTimer) {
					electionTimer = null;
					startElection();
				} else if (timeout.timer() == leaderContactTimer) {
					leaderContactTimer = null;
				} else if (timeout.timer() == heartbeatTimer) {
					heartbeatTimer = null;
					if (role == Role.LEADER) {
						refreshLeaderContact();
						replicateAll(true);
						scheduleHeartbeat();
					}
				}
			}
		}
	}

	private void startElection() {
		if (!membership.effective().voters().contains(identity.nodeId())) return;
		cancelLeaderContact();
		if (hardState.currentTerm() == Long.MAX_VALUE) {
			fail(new IOException("Raft term exhausted"));
			return;
		}
		votes.clear();
		persist(new RaftHardState(hardState.currentTerm() + 1, identity.nodeId()), () -> {
			votes.add(identity.nodeId());
			if (hasMajority()) {
				becomeLeader();
			} else {
				broadcast(new VoteRequest(hardState.currentTerm(), position(lastIndex())));
			}
		});
		changeRole(Role.CANDIDATE, null);
		resetElectionTimer();
	}

	private void receive(Envelope envelope) {
		if (!acceptsMessage(envelope)) return;
		if (envelope.message() instanceof VoteRequest && leaderContactTimer != null) return;
		if (envelope.message() instanceof AppendResponse response && !membership.effective().voters().contains(envelope.from())
			&& response.term() > hardState.currentTerm()) {
			AppendEntries request = inFlight.get(envelope.from());
			if (request == null || request.requestId() != response.requestId()) return;
		}
		if (envelope.message() instanceof SnapshotResponse response && !membership.effective().voters().contains(envelope.from())
			&& response.term() > hardState.currentTerm()) {
			var request = snapshotInFlight.get(envelope.from());
			if (request == null || request.requestId() != response.requestId()) return;
		}
		long term = envelope.message().term();
		if (term > hardState.currentTerm()) {
			cancelLeaderContact();
			persist(new RaftHardState(term, null), () -> handleCurrentTerm(envelope));
			votes.clear();
			cancelHeartbeatTimer();
			changeRole(Role.FOLLOWER, null);
			resetElectionTimer();
		} else {
			handleCurrentTerm(envelope);
		}
	}

	private void handleCurrentTerm(Envelope envelope) {
		switch (envelope.message()) {
			case VoteRequest request -> {
				LogPosition lastLog = position(lastIndex());
				boolean logIsCurrent = request.lastLog().term() > lastLog.term()
					|| (request.lastLog().term() == lastLog.term() && request.lastLog().index() >= lastLog.index());
				boolean mayVote = membership.effective().voters().contains(identity.nodeId())
					&& membership.effective().voters().contains(envelope.from())
					&& (hardState.votedFor() == null || hardState.votedFor().equals(envelope.from()));
				if (request.term() == hardState.currentTerm() && mayVote && logIsCurrent) {
					Runnable grant = () -> {
						resetElectionTimer();
						send(envelope.from(), new VoteResponse(hardState.currentTerm(), request.term(), true));
					};
					if (envelope.from().equals(hardState.votedFor())) {
						grant.run();
					} else {
						persist(new RaftHardState(hardState.currentTerm(), envelope.from()), grant);
					}
				} else {
					send(envelope.from(), new VoteResponse(hardState.currentTerm(), request.term(), false));
				}
			}
			case VoteResponse response -> {
				if (role == Role.CANDIDATE && response.term() == hardState.currentTerm()
					&& response.electionTerm() == hardState.currentTerm() && response.granted()) {
					votes.add(envelope.from());
					if (hasMajority()) {
						becomeLeader();
					}
				}
			}
			case AppendEntries request -> receiveAppend(envelope.from(), request);
			case AppendResponse response -> receiveAppendResponse(envelope.from(), response);
			case InstallSnapshot request -> receiveSnapshot(envelope.from(), request);
			case SnapshotResponse response -> receiveSnapshotResponse(envelope.from(), response);
		}
	}

	private boolean hasMajority() {
		return membership.effective().hasQuorum(votes);
	}

	private void becomeLeader() {
		cancelElectionTimer();
		nextIndex.clear();
		matchIndex.clear();
		inFlight.clear();
		snapshotInFlight.clear();
		for (String voter : replicationMembers()) {
			nextIndex.put(voter, lastIndex() + 1);
			matchIndex.put(voter, voter.equals(identity.nodeId()) ? durableLastIndex() : 0L);
		}
		changeRole(Role.LEADER, identity.nodeId());
		refreshLeaderContact();
		try {
			appendLocal(RaftLogEntry.Kind.NO_OP, "");
		} catch (IllegalArgumentException e) {
			fail(new IOException("Cannot persist the new leader's no-op within log capacity", e));
		}
	}

	private void scheduleHeartbeat() {
		heartbeatTimer = new ScheduleTimer(TimerKind.HEARTBEAT, ++sequence, timing.heartbeatMillis());
		effects.add(heartbeatTimer);
	}

	private void persist(RaftHardState state, Runnable continuation) {
		persist(new RaftPersistentState(state, log, commitIndex, checkpoint), continuation);
	}

	private void persist(RaftPersistentState state, Runnable continuation) { persist(state, continuation, false); }

	private void persist(RaftPersistentState state, Runnable continuation, boolean restore) {
		var updatedMembership = RaftConfiguration.history(initialVoters, state.log(), state.commitIndex(), state.snapshot());
		hardState = state.hardState();
		log = state.log();
		checkpoint = state.snapshot();
		commitIndex = state.commitIndex();
		membership = updatedMembership;
		syncReplicationMembers();
		pendingWrite = new WriteState(++sequence, state, restore);
		afterPersistence = continuation;
		effects.add(pendingWrite);
	}

	private void resetElectionTimer() {
		cancelElectionTimer();
		if (!membership.effective().voters().contains(identity.nodeId())) return;
		electionTimer = new ScheduleTimer(TimerKind.ELECTION, ++sequence,
			random.nextLong(timing.electionMinMillis(), timing.electionMaxMillis()));
		effects.add(electionTimer);
	}

	private void cancelElectionTimer() {
		if (electionTimer != null) {
			effects.add(new CancelTimer(electionTimer));
			electionTimer = null;
		}
	}

	private void cancelHeartbeatTimer() {
		if (heartbeatTimer != null) {
			effects.add(new CancelTimer(heartbeatTimer));
			heartbeatTimer = null;
		}
	}

	private void cancelTimers() {
		cancelLeaderContact();
		cancelElectionTimer();
		cancelHeartbeatTimer();
	}

	private void changeRole(Role newRole, String newLeader) {
		if (newRole != Role.LEADER) {
			nextIndex.clear();
			matchIndex.clear();
			inFlight.clear();
			snapshotInFlight.clear();
		}
		if (role != newRole || !Objects.equals(leaderId, newLeader)) {
			role = newRole;
			leaderId = newLeader;
			effects.add(new RoleChanged(snapshot()));
		}
	}

	private LogPosition position(long index) {
		if (index == baseIndex()) return new LogPosition(index, checkpoint == null ? 0 : checkpoint.lastIncludedTerm());
		if (index < baseIndex() || index > lastIndex()) throw new IllegalArgumentException("Position is outside retained log");
		return new LogPosition(index, log.get(Math.toIntExact(index - baseIndex() - 1)).term());
	}

	private void appendLocal(RaftLogEntry.Kind kind, String data) {
		var updated = new ArrayList<>(log);
		updated.add(new RaftLogEntry(lastIndex() + 1, hardState.currentTerm(), kind, data));
		persist(new RaftPersistentState(hardState, updated, commitIndex, checkpoint), () -> {
			matchIndex.put(identity.nodeId(), lastIndex());
			replicateAll(false);
			advanceCommit();
			if (role == Role.LEADER && heartbeatTimer == null) {
				scheduleHeartbeat();
			}
		});
	}

	private void replicateAll(boolean retry) {
		for (String voter : List.copyOf(nextIndex.keySet())) {
			if (!voter.equals(identity.nodeId())) {
				sendAppend(voter, retry);
			}
		}
	}

	private void sendAppend(String voter, boolean retry) {
		InstallSnapshot snapshotRequest = snapshotInFlight.get(voter);
		if (snapshotRequest != null) {
			if (retry) send(voter, snapshotRequest);
			return;
		}
		if (nextIndex.get(voter) <= baseIndex()) {
			inFlight.remove(voter);
			var transfer = new InstallSnapshot(hardState.currentTerm(), ++sequence, durableCheckpoint);
			snapshotInFlight.put(voter, transfer); send(voter, transfer); return;
		}
		AppendEntries request = inFlight.get(voter);
		if (request == null) {
			long next = nextIndex.get(voter);
			int start = Math.toIntExact(next - baseIndex() - 1);
			int end = Math.min(log.size(), start + MAX_APPEND_ENTRIES);
			request = new AppendEntries(hardState.currentTerm(), ++sequence, position(next - 1),
				log.subList(start, end), commitIndex);
			inFlight.put(voter, request);
		} else if (!retry) {
			return;
		}
		send(voter, request);
	}

	private void receiveAppend(String sender, AppendEntries request) {
		if (request.term() < hardState.currentTerm()) {
			rejectAppend(sender, request, lastIndex() + 1);
			return;
		}
		votes.clear();
		cancelHeartbeatTimer();
		changeRole(Role.FOLLOWER, sender);
		resetElectionTimer();
		refreshLeaderContact();
		long previous = request.previous().index();
		if (previous < baseIndex()) { rejectAppend(sender, request, baseIndex() + 1); return; }
		if (previous > lastIndex() || position(previous).term() != request.previous().term()) {
			rejectAppend(sender, request, Math.max(1, Math.min(previous, lastIndex() + 1)));
			return;
		}
		var updated = new ArrayList<>(log);
		for (int i = 0; i < request.entries().size(); i++) {
			RaftLogEntry entry = request.entries().get(i);
			int offset = Math.toIntExact(entry.index() - baseIndex() - 1);
			if (offset < updated.size()) {
				RaftLogEntry existing = updated.get(offset);
				if (existing.equals(entry)) {
					continue;
				}
				if (entry.index() <= commitIndex || existing.term() == entry.term()) {
					fail(new IOException("Leader attempted to replace committed or same-term conflicting data"));
					return;
				}
				updated.subList(offset, updated.size()).clear();
			}
			updated.add(entry);
		}
		long covered = previous + request.entries().size();
		long newCommit = Math.max(commitIndex, Math.min(request.leaderCommit(), covered));
		Runnable acknowledge = () -> {
			emitCommitted();
			reconcileMembership();
			send(sender, new AppendResponse(hardState.currentTerm(), request.requestId(), true, covered, covered + 1));
		};
		if (!updated.equals(log) || newCommit != commitIndex) {
			try {
				persist(new RaftPersistentState(hardState, updated, newCommit, checkpoint), acknowledge);
			} catch (IllegalArgumentException e) {
				fail(new IOException("Replicated log exceeds supported capacity", e));
			}
		} else {
			acknowledge.run();
		}
	}

	private void receiveSnapshot(String sender, InstallSnapshot request) {
		var incoming = request.snapshot();
		if (request.term() < hardState.currentTerm()) {
			send(sender, new SnapshotResponse(hardState.currentTerm(), request.requestId(), false, incoming.lastIncludedIndex())); return;
		}
		votes.clear(); cancelHeartbeatTimer(); changeRole(Role.FOLLOWER, sender); resetElectionTimer(); refreshLeaderContact();
		if (incoming.lastIncludedIndex() <= durableCommitIndex) {
			if (incoming.lastIncludedIndex() >= baseIndex()
				&& position(incoming.lastIncludedIndex()).term() != incoming.lastIncludedTerm()) {
				fail(new IOException("Snapshot conflicts with committed history")); return;
			}
			send(sender, new SnapshotResponse(hardState.currentTerm(), request.requestId(), true, incoming.lastIncludedIndex())); return;
		}
		List<RaftLogEntry> tail = List.of();
		if (incoming.lastIncludedIndex() <= lastIndex()
			&& position(incoming.lastIncludedIndex()).term() == incoming.lastIncludedTerm()) {
			var prefix = log.stream().filter(entry -> entry.index() <= incoming.lastIncludedIndex()).toList();
			var expected = RaftConfiguration.history(initialVoters, prefix, incoming.lastIncludedIndex(), checkpoint);
			if (!incoming.configuration().equals(expected.effective()) || incoming.configurationIndex() != expected.index()) {
				fail(new IOException("Snapshot conflicts with matching log membership")); return;
			}
			tail = log.subList(Math.toIntExact(incoming.lastIncludedIndex() - baseIndex()), log.size());
		}
		try {
			persist(new RaftPersistentState(hardState, tail, incoming.lastIncludedIndex(), incoming), () -> {
				emitCommitted(); reconcileMembership();
				send(sender, new SnapshotResponse(hardState.currentTerm(), request.requestId(), true, incoming.lastIncludedIndex()));
			}, true);
		} catch (IllegalArgumentException e) { fail(new IOException("Invalid installed snapshot", e)); }
	}

	private void receiveSnapshotResponse(String sender, SnapshotResponse response) {
		var request = snapshotInFlight.get(sender);
		if (role != Role.LEADER || request == null || response.term() != hardState.currentTerm()
			|| request.requestId() != response.requestId() || response.lastIncludedIndex() != request.snapshot().lastIncludedIndex()) return;
		if (!response.accepted()) return;
		snapshotInFlight.remove(sender);
		matchIndex.put(sender, Math.max(matchIndex.get(sender), response.lastIncludedIndex()));
		nextIndex.put(sender, matchIndex.get(sender) + 1); advanceCommit();
		if (role == Role.LEADER && pendingWrite == null && nextIndex.containsKey(sender)) sendAppend(sender, false);
	}

	private long baseIndex() { return checkpoint == null ? 0 : checkpoint.lastIncludedIndex(); }
	private long lastIndex() { return baseIndex() + log.size(); }
	private long durableBaseIndex() { return durableCheckpoint == null ? 0 : durableCheckpoint.lastIncludedIndex(); }
	private long durableLastIndex() { return durableBaseIndex() + durableLog.size(); }

	private void rejectAppend(String voter, AppendEntries request, long hint) {
		send(voter, new AppendResponse(hardState.currentTerm(), request.requestId(), false, 0, hint));
	}

	private void receiveAppendResponse(String voter, AppendResponse response) {
		if (role != Role.LEADER || (response.term() != hardState.currentTerm() && response.success())) {
			return;
		}
		AppendEntries request = inFlight.get(voter);
		if (request == null || request.requestId() != response.requestId()) {
			return;
		}
		long covered = request.previous().index() + request.entries().size();
		if (response.success() && response.matchIndex() != covered) {
			return;
		}
		inFlight.remove(voter);
		if (response.success()) {
			matchIndex.put(voter, Math.max(matchIndex.get(voter), covered));
			nextIndex.put(voter, matchIndex.get(voter) + 1);
			advanceCommit();
			if (role == Role.LEADER && pendingWrite == null && nextIndex.containsKey(voter)
				&& (nextIndex.get(voter) <= lastIndex() || request.leaderCommit() < commitIndex)) {
				sendAppend(voter, false);
			}
		} else {
			long next = Math.max(matchIndex.get(voter) + 1,
				Math.max(1, response.nextIndexHint() > nextIndex.get(voter)
					? Math.min(lastIndex() + 1, response.nextIndexHint())
					: Math.min(nextIndex.get(voter) - 1, response.nextIndexHint())));
			nextIndex.put(voter, next);
			sendAppend(voter, false);
		}
	}

	private void advanceCommit() {
		for (long index = lastIndex(); index > commitIndex; index--) {
			if (position(index).term() != hardState.currentTerm()) {
				continue;
			}
			Set<String> supported = new HashSet<>();
			for (var progress : matchIndex.entrySet()) if (progress.getValue() >= index) supported.add(progress.getKey());
			if (membership.effective().hasQuorum(supported)) {
				persist(new RaftPersistentState(hardState, log, index, checkpoint), () -> {
					emitCommitted();
					replicateAll(false);
					reconcileMembership();
				});
				return;
			}
		}
	}

	private void emitCommitted() {
		while (lastEmittedIndex < durableCommitIndex) {
			effects.add(new Committed(durableLog.get(Math.toIntExact(lastEmittedIndex - durableBaseIndex()))));
			lastEmittedIndex++;
		}
	}

	private void broadcast(Message message) {
		for (String voter : membership.effective().voters()) {
			if (!voter.equals(identity.nodeId())) {
				send(voter, message);
			}
		}
	}

	private void send(String voter, Message message) {
		effects.add(new Send(new Envelope(identity.groupName(), clusterId, identity.nodeId(), voter, message)));
	}

	boolean observesTerm(Envelope envelope) {
		return acceptsMessage(envelope)
			&& (!(envelope.message() instanceof VoteRequest) || leaderContactTimer == null)
			&& (!(envelope.message() instanceof AppendResponse || envelope.message() instanceof SnapshotResponse)
				|| membership.effective().voters().contains(envelope.from()));
	}

	private boolean acceptsMessage(Envelope envelope) {
		// Transport authenticates cluster admission for leader replication requests.
		if (envelope.message() instanceof AppendEntries || envelope.message() instanceof InstallSnapshot) return true;
		if (envelope.message() instanceof AppendResponse || envelope.message() instanceof SnapshotResponse) {
			return nextIndex.containsKey(envelope.from());
		}
		return membership.effective().voters().contains(identity.nodeId())
			&& membership.effective().voters().contains(envelope.from());
	}

	private Set<String> replicationMembers() {
		var all = new TreeSet<>(membership.effective().members());
		if (!membership.effective().joint() && membership.previous().joint() && membership.index() > durableCommitIndex) {
			all.addAll(membership.previous().voters());
		}
		return all;
	}

	private void syncReplicationMembers() {
		if (role != Role.LEADER) return;
		var all = new HashSet<>(replicationMembers()); all.add(identity.nodeId());
		nextIndex.keySet().retainAll(all); matchIndex.keySet().retainAll(all);
		inFlight.keySet().retainAll(all); snapshotInFlight.keySet().retainAll(all);
		for (String member : all) {
			nextIndex.putIfAbsent(member, lastIndex() + 1);
			matchIndex.putIfAbsent(member, member.equals(identity.nodeId()) ? durableLastIndex() : 0L);
		}
	}

	private void reconcileMembership() {
		if (role == Role.LEADER) {
			if (membership.effective().joint() && membership.index() <= durableCommitIndex && pendingWrite == null) {
				try {
					appendLocal(RaftLogEntry.Kind.CONFIGURATION,
						RaftConfiguration.stable(membership.effective().newVoters(), membership.effective().learners()).encode());
				} catch (IllegalArgumentException e) { fail(new IOException("Cannot finalize membership within log capacity", e)); }
			} else if (!membership.effective().joint() && membership.index() <= durableCommitIndex
				&& !membership.effective().voters().contains(identity.nodeId())) {
				cancelTimers(); votes.clear(); changeRole(Role.FOLLOWER, null);
			} else syncReplicationMembers();
		} else resetElectionTimer();
	}

	private void refreshLeaderContact() {
		cancelLeaderContact();
		leaderContactTimer = new ScheduleTimer(TimerKind.LEADER_CONTACT, ++sequence, timing.electionMinMillis());
		effects.add(leaderContactTimer);
	}

	private void cancelLeaderContact() {
		if (leaderContactTimer != null) { effects.add(new CancelTimer(leaderContactTimer)); leaderContactTimer = null; }
	}

	private void fail(IOException cause) {
		deferred.clear();
		pendingWrite = null;
		afterPersistence = null;
		votes.clear();
		cancelTimers();
		changeRole(Role.FAILED, null);
		effects.add(new Failure(cause));
	}

	private List<Effect> drain() {
		List<Effect> batch = List.copyOf(effects);
		effects.clear();
		return batch;
	}

	private static void requireTerm(long term, boolean positive) {
		if (term < (positive ? 1 : 0)) {
			throw new IllegalArgumentException("Invalid message term");
		}
	}
}
