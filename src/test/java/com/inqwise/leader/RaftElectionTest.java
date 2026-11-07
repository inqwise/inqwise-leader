package com.inqwise.leader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.inqwise.leader.RaftElection.Effect;
import com.inqwise.leader.RaftElection.Envelope;
import com.inqwise.leader.RaftElection.AppendEntries;
import com.inqwise.leader.RaftElection.AppendResponse;
import com.inqwise.leader.RaftElection.LogPosition;
import com.inqwise.leader.RaftElection.Role;
import com.inqwise.leader.RaftElection.ScheduleTimer;
import com.inqwise.leader.RaftElection.Send;
import com.inqwise.leader.RaftElection.TimerKind;
import com.inqwise.leader.RaftElection.VoteRequest;
import com.inqwise.leader.RaftElection.VoteResponse;
import com.inqwise.leader.RaftElection.WriteState;

class RaftElectionTest {
	private static final Set<String> VOTERS = Set.of("a", "b", "c");
	private static final LogPosition EMPTY_LOG = new LogPosition(0, 0);

	private RaftElection node(String id, Set<String> voters, RaftHardState state, LogPosition log) {
		List<RaftLogEntry> entries = java.util.stream.LongStream.rangeClosed(1, log.index())
			.mapToObj(index -> new RaftLogEntry(index, log.term(), RaftLogEntry.Kind.NO_OP, "")).toList();
		return new RaftElection(new RaftIdentity(id, "group"), "cluster", voters,
			new RaftPersistentState(state, entries, 0),
			new RaftElection.Timing(100, 200, 25), new Random(42));
	}

	private AppendEntries heartbeat(long term) {
		return new AppendEntries(term, 1, EMPTY_LOG, List.of(), 0);
	}

	private RaftElection node() {
		return node("a", VOTERS, new RaftHardState(0, null), EMPTY_LOG);
	}

	private Envelope from(String id, RaftElection.Message message) {
		return new Envelope("group", "cluster", id, "a", message);
	}

	private static <T extends Effect> T first(List<Effect> effects, Class<T> type) {
		return effects.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
	}

	private static List<Send> sends(List<Effect> effects) {
		return effects.stream().filter(Send.class::isInstance).map(Send.class::cast).toList();
	}

	private static List<Effect> confirmWrites(RaftElection node, List<Effect> initial) {
		var all = new ArrayList<>(initial);
		var pending = new ArrayDeque<WriteState>();
		initial.stream().filter(WriteState.class::isInstance).map(WriteState.class::cast).forEach(pending::add);
		while (!pending.isEmpty()) {
			List<Effect> next = node.onPersistenceSucceeded(pending.removeFirst());
			all.addAll(next);
			next.stream().filter(WriteState.class::isInstance).map(WriteState.class::cast).forEach(pending::add);
		}
		return all;
	}

	private static List<Effect> campaign(RaftElection node) {
		return confirmWrites(node, node.onTimeout(first(node.start(), ScheduleTimer.class)));
	}

	@Test
	void testSelfVoteMustBeDurableBeforeRequestingVotes() {
		var node = node();
		List<Effect> campaign = node.onTimeout(first(node.start(), ScheduleTimer.class));
		Assertions.assertEquals(Role.CANDIDATE, node.snapshot().role());
		Assertions.assertTrue(sends(campaign).isEmpty());
		Assertions.assertEquals(new RaftHardState(0, null), node.snapshot().durableState());
		Assertions.assertEquals(new RaftHardState(1, "a"), first(campaign, WriteState.class).state());
		Assertions.assertTrue(node.snapshot().votes().isEmpty());
		List<Send> requests = sends(node.onPersistenceSucceeded(first(campaign, WriteState.class)));
		Assertions.assertEquals(2, requests.size());
		Assertions.assertTrue(requests.stream().allMatch(send -> send.envelope().message() instanceof VoteRequest));
		Assertions.assertEquals(Set.of("a"), node.snapshot().votes());
	}

	@Test
	void testCompetingRequestsAreSerializedBehindVoteDurability() {
		var node = node();
		node.start();
		List<Effect> firstRequest = node.onMessage(from("b", new VoteRequest(1, EMPTY_LOG)));
		Assertions.assertTrue(sends(firstRequest).isEmpty());
		Assertions.assertTrue(node.onMessage(from("c", new VoteRequest(1, EMPTY_LOG))).isEmpty());
		List<Send> replies = sends(confirmWrites(node, firstRequest));
		Assertions.assertEquals(2, replies.size());
		Assertions.assertEquals(new VoteResponse(1, 1, true), replies.get(0).envelope().message());
		Assertions.assertEquals("b", replies.get(0).envelope().to());
		Assertions.assertEquals(new VoteResponse(1, 1, false), replies.get(1).envelope().message());
		Assertions.assertEquals(new RaftHardState(1, "b"), node.snapshot().durableState());
	}

	@Test
	void testOnlyDistinctCurrentElectionVotesCount() {
		Set<String> five = Set.of("a", "b", "c", "d", "e");
		var node = node("a", five, new RaftHardState(0, null), EMPTY_LOG);
		campaign(node);
		node.onMessage(from("b", new VoteResponse(1, 1, true)));
		node.onMessage(from("b", new VoteResponse(1, 1, true)));
		node.onMessage(from("c", new VoteResponse(0, 1, true)));
		node.onMessage(from("d", new VoteResponse(1, 2, true)));
		node.onMessage(from("outsider", new VoteResponse(100, 1, true)));
		node.onMessage(from("e", new VoteResponse(1, 1, false)));
		Assertions.assertEquals(Role.CANDIDATE, node.snapshot().role());
		Assertions.assertEquals(Set.of("a", "b"), node.snapshot().votes());
		List<Effect> elected = confirmWrites(node, node.onMessage(from("c", new VoteResponse(1, 1, true))));
		Assertions.assertEquals(Role.LEADER, node.snapshot().role());
		Assertions.assertEquals(4, sends(elected).size());
		Assertions.assertTrue(sends(elected).stream()
			.allMatch(send -> send.envelope().message() instanceof AppendEntries));
		Assertions.assertTrue(node.onMessage(from("c", new VoteResponse(1, 1, true))).isEmpty());
	}

	@Test
	void testSingleVoterWaitsForDurableSelfVote() {
		var node = node("a", Set.of("a"), new RaftHardState(0, null), EMPTY_LOG);
		List<Effect> campaigning = node.onTimeout(first(node.start(), ScheduleTimer.class));
		Assertions.assertEquals(Role.CANDIDATE, node.snapshot().role());
		node.onPersistenceSucceeded(first(campaigning, WriteState.class));
		Assertions.assertEquals(Role.LEADER, node.snapshot().role());
	}

	@Test
	void testHigherTermResponseImmediatelyDemotesLeaderAndPersists() {
		var node = node();
		campaign(node);
		List<Effect> elected = confirmWrites(node, node.onMessage(from("b", new VoteResponse(1, 1, true))));
		ScheduleTimer heartbeat = first(elected.stream()
			.filter(effect -> effect instanceof ScheduleTimer timer && timer.kind() == TimerKind.HEARTBEAT).toList(),
			ScheduleTimer.class);
		List<Effect> demoted = node.onMessage(from("b", new AppendResponse(4, 1, false, 0, 1)));
		Assertions.assertEquals(Role.FOLLOWER, node.snapshot().role());
		Assertions.assertEquals(4, node.snapshot().hardState().currentTerm());
		Assertions.assertEquals(1, node.snapshot().durableState().currentTerm());
		Assertions.assertTrue(node.onTimeout(heartbeat).isEmpty());
		Assertions.assertTrue(sends(demoted).isEmpty());
		confirmWrites(node, demoted);
		Assertions.assertEquals(new RaftHardState(4, null), node.snapshot().durableState());
	}

	@Test
	void testCandidateAcceptsSameTermHeartbeatWithoutClearingVote() {
		var node = node();
		campaign(node);
		List<Effect> reply = node.onMessage(from("b", heartbeat(1)));
		Assertions.assertEquals(Role.FOLLOWER, node.snapshot().role());
		Assertions.assertEquals("b", node.snapshot().leaderId());
		Assertions.assertEquals(new RaftHardState(1, "a"), node.snapshot().hardState());
		Assertions.assertEquals(new AppendResponse(1, 1, true, 0, 1), first(reply, Send.class).envelope().message());
	}

	@Test
	void testStaleHeartbeatDoesNotResetElectionTimer() {
		var node = node("a", VOTERS, new RaftHardState(5, null), EMPTY_LOG);
		ScheduleTimer initial = first(node.start(), ScheduleTimer.class);
		List<Effect> reply = node.onMessage(from("b", heartbeat(4)));
		Assertions.assertEquals(new AppendResponse(5, 1, false, 0, 1), first(reply, Send.class).envelope().message());
		Assertions.assertFalse(reply.stream().anyMatch(ScheduleTimer.class::isInstance));
		Assertions.assertNotNull(first(node.onTimeout(initial), WriteState.class));
	}

	@ParameterizedTest
	@ValueSource(strings = { "group", "cluster", "receiver", "sender" })
	void testForeignMessagesCannotChangeState(String mismatch) {
		var node = node();
		node.start();
		var before = node.snapshot();
		var request = new Envelope(mismatch.equals("group") ? "other" : "group",
			mismatch.equals("cluster") ? "other" : "cluster", mismatch.equals("sender") ? "x" : "b",
			mismatch.equals("receiver") ? "c" : "a", new VoteRequest(100, EMPTY_LOG));
		Assertions.assertTrue(node.onMessage(request).isEmpty());
		Assertions.assertEquals(before, node.snapshot());
	}

	@Test
	void testLogFreshnessUsesTermBeforeIndex() {
		var node = node("a", VOTERS, new RaftHardState(5, null), new LogPosition(10, 4));
		node.start();
		List<Effect> olderTerm = node.onMessage(from("b", new VoteRequest(5, new LogPosition(100, 3))));
		Assertions.assertEquals(new VoteResponse(5, 5, false), first(olderTerm, Send.class).envelope().message());
		List<Effect> shorter = node.onMessage(from("b", new VoteRequest(5, new LogPosition(9, 4))));
		Assertions.assertEquals(new VoteResponse(5, 5, false), first(shorter, Send.class).envelope().message());
		List<Effect> newerTerm = confirmWrites(node,
			node.onMessage(from("b", new VoteRequest(5, new LogPosition(1, 5)))));
		Assertions.assertEquals(new VoteResponse(5, 5, true), first(newerTerm, Send.class).envelope().message());
	}

	@Test
	void testStorageFailureStopsAllParticipation() {
		var node = node();
		ScheduleTimer original = first(node.start(), ScheduleTimer.class);
		List<Effect> pending = node.onTimeout(original);
		WriteState write = first(pending, WriteState.class);
		List<Effect> failed = node.onPersistenceFailed(write, new IOException("simulated disk failure"));
		Assertions.assertNotNull(first(failed, RaftElection.Failure.class));
		Assertions.assertEquals(Role.FAILED, node.snapshot().role());
		Assertions.assertTrue(node.onPersistenceSucceeded(write).isEmpty());
		Assertions.assertTrue(node.onMessage(from("b", new VoteRequest(10, EMPTY_LOG))).isEmpty());
		Assertions.assertTrue(node.onTimeout(first(pending, ScheduleTimer.class)).isEmpty());
		Assertions.assertThrows(IllegalStateException.class, node::start);
	}

	@Test
	void testCandidateRetriesWithoutPublishingAnUndurableTerm() {
		var node = node();
		List<Effect> initial = campaign(node);
		ScheduleTimer retry = first(initial, ScheduleTimer.class);
		List<Effect> next = node.onTimeout(retry);
		Assertions.assertEquals(new RaftHardState(2, "a"), first(next, WriteState.class).state());
		Assertions.assertTrue(sends(next).isEmpty());
		Assertions.assertEquals(new RaftHardState(1, "a"), node.snapshot().durableState());
		ScheduleTimer nextTimer = first(next, ScheduleTimer.class);
		Assertions.assertTrue(nextTimer.delayMillis() >= 100 && nextTimer.delayMillis() < 200);
		Assertions.assertTrue(node.onTimeout(retry).isEmpty());
		Assertions.assertEquals(2, sends(confirmWrites(node, next)).size());
	}

	@Test
	void testVoteGrantFailureCannotEmitAnAffirmativeResponse() {
		var node = node();
		node.start();
		WriteState term = first(node.onMessage(from("b", new VoteRequest(1, EMPTY_LOG))), WriteState.class);
		List<Effect> vote = node.onPersistenceSucceeded(term);
		Assertions.assertEquals(new RaftHardState(1, "b"), first(vote, WriteState.class).state());
		Assertions.assertTrue(sends(vote).isEmpty());
		List<Effect> failed = node.onPersistenceFailed(first(vote, WriteState.class), new IOException("disk failure"));
		Assertions.assertTrue(sends(failed).isEmpty());
		Assertions.assertEquals(Role.FAILED, node.snapshot().role());
		Assertions.assertEquals(new RaftHardState(1, null), node.snapshot().durableState());
	}

	@Test
	void testPersistenceBacklogIsBoundedAndFailsClosed() {
		var node = node();
		WriteState pending = first(node.onTimeout(first(node.start(), ScheduleTimer.class)), WriteState.class);
		List<Effect> failure = List.of();
		for (int i = 0; i < 1025; i++) {
			failure = node.onMessage(from("b", heartbeat(1)));
			Assertions.assertTrue(sends(failure).isEmpty());
		}
		Assertions.assertEquals(Role.FAILED, node.snapshot().role());
		Assertions.assertNotNull(first(failure, RaftElection.Failure.class));
		Assertions.assertTrue(node.onPersistenceSucceeded(pending).isEmpty());
	}

	@Test
	void testStaleTokensCannotAffectStoppedOrRecoveredEngine() {
		var old = node();
		ScheduleTimer timer = first(old.start(), ScheduleTimer.class);
		List<Effect> pending = old.onTimeout(timer);
		WriteState write = first(pending, WriteState.class);
		old.stop();
		Assertions.assertTrue(old.stop().isEmpty());
		Assertions.assertTrue(old.onPersistenceSucceeded(write).isEmpty());
		Assertions.assertTrue(old.onTimeout(first(pending, ScheduleTimer.class)).isEmpty());
		Assertions.assertThrows(IllegalStateException.class, old::start);
		var recovered = node();
		ScheduleTimer newTimer = first(recovered.start(), ScheduleTimer.class);
		Assertions.assertTrue(recovered.onTimeout(timer).isEmpty());
		WriteState current = first(recovered.onTimeout(newTimer), WriteState.class);
		Assertions.assertTrue(recovered.onPersistenceSucceeded(write).isEmpty());
		Assertions.assertTrue(recovered.onPersistenceSucceeded(
			new WriteState(current.sequence(), current.persistentState())).isEmpty());
		Assertions.assertTrue(recovered.snapshot().persistencePending());
		recovered.onPersistenceSucceeded(current);
		Assertions.assertFalse(recovered.snapshot().persistencePending());
	}

	@Test
	void testTermExhaustionFailsClosed() {
		var node = node("a", VOTERS, new RaftHardState(Long.MAX_VALUE, null), EMPTY_LOG);
		List<Effect> exhausted = node.onTimeout(first(node.start(), ScheduleTimer.class));
		Assertions.assertEquals(Role.FAILED, node.snapshot().role());
		Assertions.assertNotNull(first(exhausted, RaftElection.Failure.class));
		Assertions.assertTrue(sends(exhausted).isEmpty());
	}

	@Test
	void testVoteSurvivesRealFileStoreRestart(@TempDir Path directory) throws Exception {
		try (var store = FileRaftStore.open(directory, "group", "a")) {
			var node = node("a", VOTERS, store.hardState(), EMPTY_LOG);
			node.start();
			List<Effect> batch = node.onMessage(from("b", new VoteRequest(1, EMPTY_LOG)));
			while (batch.stream().anyMatch(WriteState.class::isInstance)) {
				WriteState write = first(batch, WriteState.class);
				Assertions.assertTrue(sends(batch).isEmpty());
				store.savePersistentState(write.persistentState());
				batch = node.onPersistenceSucceeded(write);
			}
			Assertions.assertEquals(new VoteResponse(1, 1, true), first(batch, Send.class).envelope().message());
		}
		try (var store = FileRaftStore.open(directory, "group", "a")) {
			var node = node("a", VOTERS, store.hardState(), EMPTY_LOG);
			node.start();
			List<Effect> denied = node.onMessage(from("c", new VoteRequest(1, EMPTY_LOG)));
			Assertions.assertEquals(new VoteResponse(1, 1, false), first(denied, Send.class).envelope().message());
		}
	}

	@Test
	void testSimultaneousSplitVotesRecoverThroughRandomizedTimeouts() {
		var simulation = new ElectionSimulator(42, List.of("a", "b", "c"));
		for (String member : List.of("a", "b", "c")) {
			simulation.forceElection(member);
		}
		simulation.advance(50);
		Assertions.assertTrue(simulation.elected.isEmpty());
		simulation.advance(1000);
		String leader = simulation.soleLeader();
		Assertions.assertTrue(simulation.nodes.get(leader).snapshot().hardState().currentTerm() >= 2);
	}

	@Test
	void testPartitionedLeaderRejoinsHigherTermMajority() {
		var simulation = new ElectionSimulator(7, List.of("a", "b", "c"));
		simulation.advance(1000);
		String former = simulation.soleLeader();
		long originalTerm = simulation.nodes.get(former).snapshot().hardState().currentTerm();
		simulation.isolated.add(former);
		simulation.advance(1000);
		List<String> replacement = simulation.nodes.entrySet().stream()
			.filter(entry -> !entry.getKey().equals(former) && entry.getValue().snapshot().role() == Role.LEADER)
			.map(java.util.Map.Entry::getKey).toList();
		Assertions.assertEquals(1, replacement.size());
		Assertions.assertTrue(simulation.nodes.get(replacement.getFirst()).snapshot()
			.hardState().currentTerm() > originalTerm);
		simulation.isolated.clear();
		simulation.advance(1000);
		Assertions.assertNotEquals(former, simulation.soleLeader());
		Assertions.assertEquals(Role.FOLLOWER, simulation.nodes.get(former).snapshot().role());
	}

	@Test
	void testMinorityCannotElect() {
		var simulation = new ElectionSimulator(99, List.of("a", "b", "c"));
		simulation.isolated.addAll(VOTERS);
		simulation.advance(2000);
		Assertions.assertTrue(simulation.elected.isEmpty());
		simulation.isolated.clear();
		simulation.advance(1000);
		simulation.soleLeader();
	}

	@ParameterizedTest(name = "fault schedule seed={0}")
	@ValueSource(longs = { 1, 2, 7, 19, 42, 99, 123, 997, 2026, 654321 })
	void testRandomFaultSchedulesPreserveSafetyAndRecover(long seed) {
		var simulation = new ElectionSimulator(seed, List.of("a", "b", "c", "d", "e"));
		simulation.dropPercent = 30;
		simulation.maxNetworkDelay = 40;
		simulation.persistenceDelay = 8;
		simulation.duplicateMessages = true;
		var faults = new Random(seed);
		var members = List.of("a", "b", "c", "d", "e");
		for (int i = 0; i < 40; i++) {
			String affected = members.get(faults.nextInt(members.size()));
			switch (faults.nextInt(3)) {
				case 0 -> simulation.isolated.add(affected);
				case 1 -> simulation.isolated.remove(affected);
				case 2 -> simulation.restart(affected);
			}
			simulation.advance(40);
		}
		simulation.isolated.clear();
		simulation.dropPercent = 0;
		simulation.maxNetworkDelay = 3;
		simulation.advance(2000);
		simulation.soleLeader();
	}

	@Test
	void testCrashBeforeDurabilityCannotLeaveVoteRequestsInFlight() {
		var simulation = new ElectionSimulator(123, List.of("a", "b", "c"));
		simulation.persistenceDelay = 20;
		simulation.forceElection("a");
		Assertions.assertEquals(1, simulation.nodes.get("a").snapshot().hardState().currentTerm());
		Assertions.assertEquals(0, simulation.disks.get("a").hardState().currentTerm());
		simulation.restart("a");
		simulation.advance(30);
		Assertions.assertEquals(new RaftHardState(0, null), simulation.disks.get("a").hardState());
		Assertions.assertTrue(simulation.history.stream().noneMatch(line -> line.contains("Send[")));
	}

	@Test
	void testCrashAfterDurabilityBeforeCallbackRetainsSelfVote() {
		var simulation = new ElectionSimulator(123, List.of("a", "b", "c"));
		simulation.forceElection("a");
		simulation.advance(0);
		Assertions.assertEquals(new RaftHardState(1, "a"), simulation.disks.get("a").hardState());
		Assertions.assertTrue(simulation.nodes.get("a").snapshot().persistencePending());
		simulation.restart("a");
		simulation.advance(5);
		Assertions.assertEquals(new RaftHardState(1, "a"), simulation.nodes.get("a").snapshot().durableState());
		Assertions.assertTrue(simulation.history.stream().noneMatch(line -> line.contains("Send[")));
	}

	@Test
	void testSeededSimulationIsRepeatable() {
		var first = new ElectionSimulator(123, List.of("a", "b", "c"));
		var second = new ElectionSimulator(123, List.of("a", "b", "c"));
		first.duplicateMessages = second.duplicateMessages = true;
		first.dropPercent = second.dropPercent = 20;
		first.advance(1500);
		second.advance(1500);
		Assertions.assertEquals(first.history, second.history);
	}

	@Test
	void testInvalidConfigurationRejected() {
		Assertions.assertAll(
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> node("a", Set.of("b", "c"), new RaftHardState(0, null), EMPTY_LOG)),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> node("a", VOTERS, new RaftHardState(0, null), new LogPosition(1, 1))),
			() -> Assertions.assertThrows(IllegalArgumentException.class, () -> new RaftElection.Timing(25, 50, 25)),
			() -> Assertions.assertThrows(IllegalArgumentException.class, () -> new LogPosition(0, 1)),
			() -> Assertions.assertThrows(IllegalArgumentException.class, () -> new VoteRequest(1, new LogPosition(1, 2))));
	}
}
