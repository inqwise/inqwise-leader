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

import com.inqwise.leader.RaftElection.AppendEntries;
import com.inqwise.leader.RaftElection.AppendResponse;
import com.inqwise.leader.RaftElection.Committed;
import com.inqwise.leader.RaftElection.Effect;
import com.inqwise.leader.RaftElection.Envelope;
import com.inqwise.leader.RaftElection.LogPosition;
import com.inqwise.leader.RaftElection.ScheduleTimer;
import com.inqwise.leader.RaftElection.Send;
import com.inqwise.leader.RaftElection.WriteState;

class RaftReplicationTest {
	private static RaftLogEntry data(long index, long term, String value) {
		return new RaftLogEntry(index, term, RaftLogEntry.Kind.DATA, value);
	}

	private static RaftElection node(RaftPersistentState state) {
		return new RaftElection(new RaftIdentity("a", "group"), "cluster", Set.of("a", "b", "c"),
			state, new RaftElection.Timing(100, 200, 25), new Random(42));
	}

	private static Envelope from(String member, RaftElection.Message message) {
		return new Envelope("group", "cluster", member, "a", message);
	}

	private static <T extends Effect> List<T> ofType(List<Effect> effects, Class<T> type) {
		return effects.stream().filter(type::isInstance).map(type::cast).toList();
	}

	private static <T extends Effect> T first(List<Effect> effects, Class<T> type) {
		return ofType(effects, type).getFirst();
	}

	private static List<Effect> confirm(RaftElection node, List<Effect> initial) {
		var effects = new ArrayList<>(initial);
		var writes = new ArrayDeque<>(ofType(initial, WriteState.class));
		while (!writes.isEmpty()) {
			List<Effect> next = node.onPersistenceSucceeded(writes.removeFirst());
			effects.addAll(next);
			writes.addAll(ofType(next, WriteState.class));
		}
		return effects;
	}

	private static AppendEntries request(List<Effect> effects, String member) {
		return ofType(effects, Send.class).stream().filter(send -> send.envelope().to().equals(member))
			.map(send -> send.envelope().message()).filter(AppendEntries.class::isInstance)
			.map(AppendEntries.class::cast).findFirst().orElseThrow();
	}

	private static AppendResponse accepted(AppendEntries request) {
		long covered = request.previous().index() + request.entries().size();
		return new AppendResponse(request.term(), request.requestId(), true, covered, covered + 1);
	}

	private static List<Effect> elect(RaftElection node) {
		confirm(node, node.onTimeout(first(node.start(), ScheduleTimer.class)));
		return confirm(node, node.onMessage(from("b",
			new RaftElection.VoteResponse(node.snapshot().hardState().currentTerm(),
				node.snapshot().hardState().currentTerm(), true))));
	}

	@Test
	void testFollowerAcknowledgesOnlyAfterLogAndCommitAreDurable() {
		var node = node(new RaftPersistentState(new RaftHardState(0, null), List.of(), 0));
		node.start();
		var request = new AppendEntries(1, 10, new LogPosition(0, 0), List.of(data(1, 1, "policy")), 1);
		List<Effect> term = node.onMessage(from("b", request));
		Assertions.assertTrue(ofType(term, Send.class).isEmpty());
		List<Effect> append = node.onPersistenceSucceeded(first(term, WriteState.class));
		Assertions.assertTrue(ofType(append, Send.class).isEmpty());
		Assertions.assertTrue(ofType(append, Committed.class).isEmpty());
		Assertions.assertEquals(0, node.replication().durableState().commitIndex());
		List<Effect> durable = node.onPersistenceSucceeded(first(append, WriteState.class));
		Assertions.assertEquals(new AppendResponse(1, 10, true, 1, 2),
			first(durable, Send.class).envelope().message());
		Assertions.assertEquals(List.of(new Committed(data(1, 1, "policy"))), ofType(durable, Committed.class));
		Assertions.assertEquals(1, node.replication().durableState().commitIndex());
		List<Effect> duplicate = node.onMessage(from("b", request));
		Assertions.assertTrue(ofType(duplicate, WriteState.class).isEmpty());
		Assertions.assertTrue(ofType(duplicate, Committed.class).isEmpty());
		Assertions.assertEquals(new AppendResponse(1, 10, true, 1, 2),
			first(duplicate, Send.class).envelope().message());
	}

	@Test
	void testConsistencyRejectionAndUncommittedSuffixRepair() {
		var prefix = data(1, 1, "committed");
		var node = node(new RaftPersistentState(new RaftHardState(3, null),
			List.of(prefix, data(2, 2, "old"), data(3, 2, "discard")), 1));
		node.start();
		List<Effect> rejected = node.onMessage(from("b",
			new AppendEntries(3, 1, new LogPosition(2, 1), List.of(data(3, 3, "new")), 2)));
		Assertions.assertEquals(new AppendResponse(3, 1, false, 0, 2),
			first(rejected, Send.class).envelope().message());
		Assertions.assertTrue(ofType(rejected, WriteState.class).isEmpty());
		List<Effect> repaired = confirm(node, node.onMessage(from("b",
			new AppendEntries(3, 2, new LogPosition(1, 1), List.of(data(2, 3, "new")), 2))));
		Assertions.assertEquals(List.of(prefix, data(2, 3, "new")), node.replication().durableState().log());
		Assertions.assertEquals(List.of(new Committed(data(2, 3, "new"))), ofType(repaired, Committed.class));
	}

	@Test
	void testHeartbeatPreservesExtraSuffixAndCommitsOnlyProvenPrefix() {
		List<RaftLogEntry> log = List.of(data(1, 1, "one"), data(2, 2, "two"), data(3, 2, "three"));
		var node = node(new RaftPersistentState(new RaftHardState(3, null), log, 0));
		node.start();
		List<Effect> heartbeat = confirm(node, node.onMessage(from("b",
			new AppendEntries(3, 1, new LogPosition(1, 1), List.of(), 3))));
		Assertions.assertEquals(log, node.replication().durableState().log());
		Assertions.assertEquals(1, node.replication().durableState().commitIndex());
		Assertions.assertEquals(new AppendResponse(3, 1, true, 1, 2),
			first(heartbeat, Send.class).envelope().message());
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void testCommittedOrSameTermConflictFailsClosed(boolean committed) {
		var node = node(new RaftPersistentState(new RaftHardState(3, null), List.of(data(1, 2, "original")),
			committed ? 1 : 0));
		node.start();
		List<Effect> failed = node.onMessage(from("b", new AppendEntries(3, 1, new LogPosition(0, 0),
			List.of(data(1, committed ? 3 : 2, "conflicting")), 0)));
		Assertions.assertEquals(RaftElection.Role.FAILED, node.snapshot().role());
		Assertions.assertFalse(ofType(failed, RaftElection.Failure.class).isEmpty());
		Assertions.assertTrue(ofType(failed, Send.class).isEmpty());
		Assertions.assertEquals(List.of(data(1, 2, "original")), node.replication().durableState().log());
	}

	@Test
	void testNoOpMustBeDurableBeforeLeaderReplicates() {
		var node = node(new RaftPersistentState(new RaftHardState(0, null), List.of(), 0));
		confirm(node, node.onTimeout(first(node.start(), ScheduleTimer.class)));
		List<Effect> elected = node.onMessage(from("b", new RaftElection.VoteResponse(1, 1, true)));
		Assertions.assertTrue(ofType(elected, Send.class).isEmpty());
		Assertions.assertTrue(node.replication().durableState().log().isEmpty());
		List<Effect> replicated = confirm(node, elected);
		AppendEntries request = request(replicated, "b");
		Assertions.assertEquals(new LogPosition(0, 0), request.previous());
		Assertions.assertEquals(List.of(new RaftLogEntry(1, 1, RaftLogEntry.Kind.NO_OP, "")), request.entries());
		Assertions.assertEquals(0, node.replication().durableState().commitIndex());
		Assertions.assertTrue(ofType(replicated, Committed.class).isEmpty());
	}

	@Test
	void testMajorityCommitIsDurableAndStaleResponsesCannotRegressProgress() {
		var node = node(new RaftPersistentState(new RaftHardState(0, null), List.of(), 0));
		AppendEntries initial = request(elect(node), "b");
		List<Effect> pending = node.onMessage(from("b", accepted(initial)));
		Assertions.assertEquals(0, node.replication().durableState().commitIndex());
		Assertions.assertTrue(ofType(pending, Committed.class).isEmpty());
		node.onMessage(from("b", accepted(initial)));
		List<Effect> committed = confirm(node, pending);
		Assertions.assertEquals(1, node.replication().durableState().commitIndex());
		Assertions.assertEquals(1, ofType(committed, Committed.class).size());
		AppendEntries next = request(committed, "b");
		Assertions.assertEquals(1, next.leaderCommit());
		Assertions.assertTrue(node.onMessage(from("b",
			new AppendResponse(1, initial.requestId(), false, 0, 1))).isEmpty());
		Assertions.assertTrue(node.onMessage(from("b",
			new AppendResponse(1, next.requestId(), true, 999, 1000))).isEmpty());
		Assertions.assertEquals(1L, node.replication().matchIndex().get("b"));
		Assertions.assertEquals(2L, node.replication().nextIndex().get("b"));
	}

	@Test
	void testPriorTermMajorityDoesNotCommitWithoutCurrentTermEntry() {
		List<RaftLogEntry> inherited = java.util.stream.LongStream.rangeClosed(1, 40)
			.mapToObj(index -> data(index, 2, "old-" + index)).toList();
		var node = node(new RaftPersistentState(new RaftHardState(2, null), inherited, 0));
		AppendEntries initial = request(elect(node), "b");
		Assertions.assertEquals(40, initial.previous().index());
		List<Effect> backtrack = node.onMessage(from("b",
			new AppendResponse(3, initial.requestId(), false, 0, 1)));
		AppendEntries oldEntries = request(backtrack, "b");
		Assertions.assertEquals(32, oldEntries.entries().size());
		List<Effect> progress = node.onMessage(from("b", accepted(oldEntries)));
		Assertions.assertEquals(0, node.replication().state().commitIndex());
		Assertions.assertTrue(ofType(progress, WriteState.class).isEmpty());
		AppendEntries current = request(progress, "b");
		Assertions.assertEquals(41, current.entries().getLast().index());
		Assertions.assertEquals(3, current.entries().getLast().term());
		List<Effect> committed = confirm(node, node.onMessage(from("b", accepted(current))));
		Assertions.assertEquals(41, node.replication().durableState().commitIndex());
		Assertions.assertEquals(41, ofType(committed, Committed.class).size());
		Assertions.assertEquals(41, ofType(committed, Committed.class).getLast().entry().index());
	}

	@Test
	void testPeriodicRetryReusesCorrelationWithoutAdvancingMatch() {
		var node = node(new RaftPersistentState(new RaftHardState(0, null), List.of(), 0));
		List<Effect> elected = elect(node);
		AppendEntries initial = request(elected, "b");
		ScheduleTimer timer = ofType(elected, ScheduleTimer.class).stream()
			.filter(value -> value.kind() == RaftElection.TimerKind.HEARTBEAT).findFirst().orElseThrow();
		Assertions.assertEquals(initial, request(node.onTimeout(timer), "b"));
		Assertions.assertEquals(0L, node.replication().matchIndex().get("b"));
	}

	@Test
	void testProposalCommitAndOrderedReplay(@TempDir Path directory) throws Exception {
		var node = node(new RaftPersistentState(new RaftHardState(0, null), List.of(), 0));
		Assertions.assertThrows(IllegalStateException.class, () -> node.propose("not-leader"));
		AppendEntries first = request(elect(node), "b");
		List<Effect> committed = confirm(node, node.onMessage(from("b", accepted(first))));
		node.onMessage(from("b", accepted(request(committed, "b"))));
		List<Effect> proposed = node.propose("membership-policy");
		Assertions.assertTrue(ofType(proposed, Send.class).isEmpty());
		Assertions.assertThrows(IllegalStateException.class, () -> node.propose("concurrent"));
		AppendEntries data = request(confirm(node, proposed), "b");
		List<Effect> applied = confirm(node, node.onMessage(from("b", accepted(data))));
		Assertions.assertEquals(List.of(new Committed(data(2, 1, "membership-policy"))), ofType(applied, Committed.class));
		try (var store = FileRaftStore.open(directory, "group", "a")) {
			store.savePersistentState(node.replication().durableState());
		}
		try (var store = FileRaftStore.open(directory, "group", "a")) {
			var recovered = node(store.persistentState());
			List<Committed> replay = ofType(recovered.start(), Committed.class);
			Assertions.assertEquals(List.of(1L, 2L), replay.stream().map(value -> value.entry().index()).toList());
			Assertions.assertTrue(recovered.start().isEmpty());
		}
	}

	@Test
	void testFullLogPreventsNewLeaderFromReplicatingWithoutRequiredNoOp() {
		List<RaftLogEntry> full = java.util.stream.LongStream.rangeClosed(1, RaftPersistentState.MAX_LOG_ENTRIES)
			.mapToObj(index -> new RaftLogEntry(index, 1, RaftLogEntry.Kind.NO_OP, "")).toList();
		var node = node(new RaftPersistentState(new RaftHardState(1, null), full, 0));
		List<Effect> failed = elect(node);
		Assertions.assertEquals(RaftElection.Role.FAILED, node.snapshot().role());
		Assertions.assertTrue(ofType(failed, Send.class).isEmpty());
		Assertions.assertFalse(ofType(failed, RaftElection.Failure.class).isEmpty());
	}

	@Test
	void testMalformedReplicationMessagesAreRejected() {
		Assertions.assertAll(
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> new AppendEntries(1, 0, new LogPosition(0, 0), List.of(), 0)),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> new AppendEntries(1, 1, new LogPosition(0, 0), List.of(data(2, 1, "gap")), 0)),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> new AppendEntries(1, 1, new LogPosition(0, 0), List.of(data(1, 2, "future-term")), 0)),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> new AppendEntries(1, 1, new LogPosition(0, 0),
					java.util.stream.LongStream.rangeClosed(1, RaftElection.MAX_APPEND_ENTRIES + 1)
						.mapToObj(index -> data(index, 1, "oversized-batch")).toList(), 0)),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> new AppendResponse(1, 1, false, 0, 0)));
	}

	@Test
	void testFollowerLogFailureDoesNotAcknowledgeOrApply() {
		var node = node(new RaftPersistentState(new RaftHardState(1, null), List.of(), 0));
		node.start();
		List<Effect> pending = node.onMessage(from("b",
			new AppendEntries(1, 1, new LogPosition(0, 0), List.of(data(1, 1, "policy")), 1)));
		List<Effect> failed = node.onPersistenceFailed(first(pending, WriteState.class), new IOException("disk failed"));
		Assertions.assertTrue(ofType(failed, Send.class).isEmpty());
		Assertions.assertTrue(ofType(failed, Committed.class).isEmpty());
		Assertions.assertTrue(node.replication().durableState().log().isEmpty());
		Assertions.assertEquals(RaftElection.Role.FAILED, node.snapshot().role());
	}

	@Test
	void testPartitionedLeaderLosesUncommittedSuffixAndCommittedDataSurvivesFailover() {
		var simulation = new ElectionSimulator(42, List.of("a", "b", "c"));
		simulation.advance(1000);
		String oldLeader = simulation.soleLeader();
		simulation.propose(oldLeader, "stable");
		simulation.advance(150);
		Assertions.assertEquals("stable", simulation.committed.get(2L).data());
		simulation.isolated.add(oldLeader);
		simulation.propose(oldLeader, "isolated");
		simulation.advance(1000);
		String replacement = simulation.nodes.entrySet().stream()
			.filter(entry -> !entry.getKey().equals(oldLeader) && entry.getValue().snapshot().role() == RaftElection.Role.LEADER)
			.map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
		Assertions.assertEquals(2, simulation.disks.get(oldLeader).commitIndex());
		simulation.propose(replacement, "replacement");
		simulation.advance(150);
		simulation.isolated.clear();
		simulation.advance(1000);
		Assertions.assertTrue(simulation.committed.values().stream().noneMatch(entry -> entry.data().equals("isolated")));
		Assertions.assertEquals(simulation.disks.get(replacement).log(), simulation.disks.get(oldLeader).log());
		simulation.crash(replacement);
		simulation.advance(1000);
		String survivor = simulation.soleLeader();
		Assertions.assertTrue(simulation.disks.get(survivor).log().stream()
			.anyMatch(entry -> entry.data().equals("replacement")));
		simulation.restart(replacement);
		simulation.advance(1000);
		Assertions.assertEquals(simulation.disks.get(survivor).log(), simulation.disks.get(replacement).log());
	}

	@ParameterizedTest(name = "replication fault seed={0}")
	@ValueSource(longs = { 2, 19, 42, 123, 2026 })
	void testReplicationSafetyDuringRandomCrashesAndPartitions(long seed) {
		var simulation = new ElectionSimulator(seed, List.of("a", "b", "c", "d", "e"));
		simulation.advance(1000);
		simulation.propose(simulation.soleLeader(), "initial-policy");
		simulation.advance(150);
		simulation.dropPercent = 25;
		simulation.maxNetworkDelay = 35;
		simulation.duplicateMessages = true;
		simulation.persistenceDelay = 4;
		var random = new Random(seed);
		var members = List.of("a", "b", "c", "d", "e");
		for (int i = 0; i < 40; i++) {
			String member = members.get(random.nextInt(members.size()));
			switch (random.nextInt(3)) {
				case 0 -> simulation.isolated.add(member);
				case 1 -> simulation.isolated.remove(member);
				case 2 -> simulation.restart(member);
			}
			for (String candidate : members) {
				var state = simulation.nodes.get(candidate).snapshot();
				if (state.role() == RaftElection.Role.LEADER && !state.persistencePending()) {
					simulation.propose(candidate, "policy-" + i + "-" + candidate);
				}
			}
			simulation.advance(40);
		}
		simulation.isolated.clear();
		simulation.dropPercent = 0;
		simulation.maxNetworkDelay = 3;
		simulation.advance(2500);
		String leader = simulation.soleLeader();
		for (String member : members) {
			Assertions.assertEquals(simulation.disks.get(leader).log(), simulation.disks.get(member).log());
			Assertions.assertEquals(simulation.disks.get(leader).commitIndex(), simulation.disks.get(member).commitIndex());
		}
		Assertions.assertTrue(simulation.committed.values().stream()
			.anyMatch(entry -> entry.data().equals("initial-policy")));
	}
}
