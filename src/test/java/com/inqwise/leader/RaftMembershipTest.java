package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.inqwise.leader.RaftElection.*;

class RaftMembershipTest {
	static final Set<String> INITIAL = Set.of("a", "b", "c");
	static final Timing TIMING = new Timing(100, 200, 25);

	@Test void jointQuorumRequiresSeparateMajoritiesAndExcludesLearners() {
		var config = new RaftConfiguration(Set.of("a", "b", "c"), Set.of("c", "d", "e"), Set.of("learner"));
		assertFalse(config.hasQuorum(Set.of("a", "b", "learner")));
		assertFalse(config.hasQuorum(Set.of("c", "d", "e"))); // Only one old voter, even with union majority.
		assertTrue(config.hasQuorum(Set.of("b", "c", "d")));
		assertEquals(config, RaftConfiguration.decode(config.encode()));
		assertThrows(IllegalArgumentException.class, () -> new RaftConfiguration(Set.of("a"), Set.of("b"), Set.of("a")));
		assertThrows(IllegalArgumentException.class, () -> RaftConfiguration.decode("membership-v1|b,a|a,b|"));
	}

	@Test void learnerDoesNotCampaignOrGrantVotesEvenWithHigherTermRequests() {
		var learner = new RaftElection(new RaftIdentity("d", "group"), "cluster", INITIAL,
			new RaftPersistentState(new RaftHardState(0, null), List.of(), 0), TIMING, new Random(1), true);
		assertTrue(learner.start().stream().noneMatch(ScheduleTimer.class::isInstance));
		assertTrue(learner.onMessage(new Envelope("group", "cluster", "a", "d", new VoteRequest(100,
			new LogPosition(0, 0)))).isEmpty());
		assertEquals(0, learner.snapshot().hardState().currentTerm());
	}

	@Test void promotionRequiresDurableLearnerCatchupAndOldOnlyQuorumCannotCommitJoint() {
		var sim = simulation(7);
		sim.advance(1000); String leader = sim.soleLeader();
		assertThrows(IllegalStateException.class, () -> sim.changeVoters(leader, Set.of("d", "e")));
		sim.isolated.addAll(Set.of("d", "e"));
		sim.addLearner(leader, "d"); sim.advance(50);
		sim.addLearner(leader, "e"); sim.advance(50);
		assertThrows(IllegalStateException.class, () -> sim.changeVoters(leader, Set.of("d", "e")));
		sim.isolated.clear(); sim.advance(100);
		long before = sim.disks.get(leader).commitIndex();
		sim.changeVoters(leader, Set.of("d", "e"));
		assertTrue(sim.nodes.get(leader).membership().effective().joint());
		assertThrows(IllegalStateException.class, () -> sim.nodes.get(leader).addLearner("f"));
		sim.isolated.addAll(Set.of("d", "e")); sim.advance(75);
		assertEquals(before, sim.disks.get(leader).commitIndex(), "old majority cannot commit the joint entry alone");
		sim.isolated.clear(); sim.advance(1000);
		String replacement = sim.soleLeader();
		assertTrue(Set.of("d", "e").contains(replacement));
		assertEquals(Set.of("d", "e"), sim.nodes.get(replacement).membership().effective().voters());
		assertFalse(sim.nodes.get(replacement).membership().effective().joint());
		sim.propose(replacement, "after-removal"); sim.advance(100);
		assertTrue(sim.committed.values().stream().anyMatch(entry -> entry.data().equals("after-removal")));
	}

	@Test void effectiveConfigurationRollsBackWithUncommittedSuffixAndRestartsFromLatestLog() {
		var learnerConfig = RaftConfiguration.stable(INITIAL, Set.of("d"));
		var joint = new RaftConfiguration(INITIAL, Set.of("b", "c", "d"), Set.of());
		var log = List.of(new RaftLogEntry(1, 1, RaftLogEntry.Kind.CONFIGURATION, learnerConfig.encode()),
			new RaftLogEntry(2, 1, RaftLogEntry.Kind.CONFIGURATION, joint.encode()));
		var node = new RaftElection(new RaftIdentity("c", "group"), "cluster", INITIAL,
			new RaftPersistentState(new RaftHardState(1, null), log, 1), TIMING, new Random(1));
		node.start(); assertTrue(node.membership().effective().joint());
		var replacement = new RaftLogEntry(2, 2, RaftLogEntry.Kind.NO_OP, "");
		confirm(node, node.onMessage(new Envelope("group", "cluster", "b", "c",
			new AppendEntries(2, 1, new LogPosition(1, 1), List.of(replacement), 1))));
		assertEquals(learnerConfig, node.membership().effective());
		assertEquals(1, node.membership().configurationIndex());
		var recovered = new RaftElection(new RaftIdentity("c", "group"), "cluster", INITIAL,
			node.replication().durableState(), TIMING, new Random(1));
		assertEquals(learnerConfig, recovered.membership().effective());
	}

	@Test void recentlyContactedFollowersIgnoreDisruptiveVotesUntilMinimumWindowExpires() {
		var node = new RaftElection(new RaftIdentity("a", "group"), "cluster", INITIAL,
			new RaftPersistentState(new RaftHardState(1, null), List.of(), 0), TIMING, new Random(1));
		node.start();
		var contacted = node.onMessage(new Envelope("group", "cluster", "b", "a",
			new AppendEntries(1, 1, new LogPosition(0, 0), List.of(), 0)));
		var guard = contacted.stream().filter(effect -> effect instanceof ScheduleTimer timer
			&& timer.kind() == TimerKind.LEADER_CONTACT).map(ScheduleTimer.class::cast).findFirst().orElseThrow();
		var disruptive = new Envelope("group", "cluster", "c", "a", new VoteRequest(100, new LogPosition(0, 0)));
		assertTrue(node.onMessage(disruptive).isEmpty()); assertEquals(1, node.snapshot().hardState().currentTerm());
		node.onTimeout(guard);
		assertTrue(node.onMessage(disruptive).stream().anyMatch(WriteState.class::isInstance));
	}

	@Test void staleFollowerAcceptsNewLeaderOutsideItsOldConfiguration() {
		var sim = simulation(12); sim.advance(1000);
		String original = sim.soleLeader();
		String stale = INITIAL.stream().filter(id -> !id.equals(original)).findFirst().orElseThrow();
		sim.crash(stale);
		sim.addLearner(original, "d"); sim.advance(100);
		sim.addLearner(original, "e"); sim.advance(100);
		sim.changeVoters(original, Set.of("d", "e")); sim.advance(1000);
		assertTrue(Set.of("d", "e").contains(sim.soleLeader()));
		sim.restart(stale);
		// Re-admission as a learner makes the new leader send a complete configuration history.
		sim.addLearner(sim.soleLeader(), stale); sim.advance(500);
		assertTrue(sim.nodes.get(stale).membership().effective().voters().contains("d"));
		assertFalse(sim.nodes.get(stale).membership().effective().voters().contains(stale));
	}

	@ParameterizedTest @ValueSource(longs = {3, 17, 29, 41, 71})
	void randomizedMembershipWithCrashesPartitionsAndReorderedMessages(long seed) {
		var sim = simulation(seed); sim.duplicateMessages = true; sim.dropPercent = 8; sim.maxNetworkDelay = 8;
		sim.advance(1000); String leader = sim.soleLeader();
		sim.addLearner(leader, "d"); sim.advance(300);
		sim.addLearner(leader, "e"); sim.advance(300);
		sim.changeVoters(leader, Set.of("b", "c", "d", "e"));
		sim.advance(2 + seed % 9); sim.crash(leader);
		sim.advance(500); sim.restart(leader);
		sim.isolated.add("d"); sim.advance(300); sim.isolated.clear();
		sim.dropPercent = 0; sim.advance(1500);
		String current = sim.soleLeader();
		// An uncommitted joint suffix may be rolled back; committed ones must finalize on recovery.
		var membership = sim.nodes.get(current).membership();
		assertFalse(membership.effective().joint());
		assertTrue(membership.effective().voters().equals(INITIAL)
			|| membership.effective().voters().equals(Set.of("b", "c", "d", "e")));
		sim.propose(current, "survived-" + seed); sim.advance(200);
		assertTrue(sim.committed.values().stream().anyMatch(entry -> entry.data().equals("survived-" + seed)));
	}

	@Test void newOnlyMajorityCannotCommitJointConfiguration() {
		var sim = simulation(19); sim.advance(1000); String leader = sim.soleLeader();
		sim.addLearner(leader, "d"); sim.advance(100); sim.addLearner(leader, "e"); sim.advance(100);
		long before = sim.disks.get(leader).commitIndex();
		sim.changeVoters(leader, Set.of("d", "e"));
		for (String old : INITIAL) if (!old.equals(leader)) sim.isolated.add(old);
		sim.advance(50);
		assertEquals(before, sim.disks.get(leader).commitIndex(), "new majority plus one old voter is insufficient");
		sim.isolated.clear(); sim.advance(1200);
		assertEquals(Set.of("d", "e"), sim.nodes.get(sim.soleLeader()).membership().effective().voters());
	}

	@ParameterizedTest @ValueSource(strings = {"a", "d"})
	void jointElectionRequiresBothMajorities(String candidate) {
		var learner = RaftConfiguration.stable(INITIAL, Set.of("d", "e"));
		var joint = new RaftConfiguration(INITIAL, Set.of("d", "e"), Set.of());
		var log = List.of(new RaftLogEntry(1, 1, RaftLogEntry.Kind.CONFIGURATION, learner.encode()),
			new RaftLogEntry(2, 1, RaftLogEntry.Kind.CONFIGURATION, joint.encode()));
		var node = new RaftElection(new RaftIdentity(candidate, "group"), "cluster", INITIAL,
			new RaftPersistentState(new RaftHardState(1, null), log, 1), TIMING, new Random(1), candidate.equals("d"));
		var timer = node.start().stream().filter(ScheduleTimer.class::isInstance).map(ScheduleTimer.class::cast)
			.findFirst().orElseThrow();
		confirm(node, node.onTimeout(timer));
		var firstVotes = candidate.equals("a") ? Set.of("b", "c") : Set.of("e");
		for (String voter : firstVotes) confirm(node, node.onMessage(new Envelope("group", "cluster", voter, candidate,
			new VoteResponse(2, 2, true))));
		assertEquals(Role.CANDIDATE, node.snapshot().role());
		var remaining = candidate.equals("a") ? Set.of("d", "e") : Set.of("a", "b");
		for (String voter : remaining) confirm(node, node.onMessage(new Envelope("group", "cluster", voter, candidate,
			new VoteResponse(2, 2, true))));
		assertEquals(Role.LEADER, node.snapshot().role());
	}

	@Test void durablyCommittedJointSurvivesCrashBeforeCallbackAndFinalizesOnSuccessor() {
		var sim = simulation(23); sim.advance(1000); String leader = sim.soleLeader();
		sim.addLearner(leader, "d"); sim.advance(100); sim.addLearner(leader, "e"); sim.advance(100);
		sim.persistenceDelay = 20; sim.changeVoters(leader, Set.of("d", "e"));
		boolean captured = false;
		for (int step = 0; step < 500; step++) {
			sim.advance(1);
			var disk = sim.disks.get(leader);
			var stored = RaftConfiguration.history(INITIAL, disk.log(), disk.commitIndex());
			if (stored.effective().joint() && stored.index() == disk.commitIndex()) { captured = true; break; }
		}
		assertTrue(captured, "must crash with joint commitment on disk before finalization");
		sim.crash(leader); sim.advance(500); sim.restart(leader); sim.advance(2500);
		var recovered = sim.nodes.get(sim.soleLeader()).membership();
		assertEquals(Set.of("d", "e"), recovered.effective().voters());
		assertFalse(recovered.effective().joint());
	}

	@Test void committedMembershipDiagnosticsWaitForDurabilityAndLearnersCanBeRemoved() {
		var node = new RaftElection(new RaftIdentity("a", "group"), "cluster", Set.of("a"),
			new RaftPersistentState(new RaftHardState(0, null), List.of(), 0), TIMING, new Random(1));
		var election = node.start().stream().filter(ScheduleTimer.class::isInstance).map(ScheduleTimer.class::cast)
			.findFirst().orElseThrow();
		confirm(node, node.onTimeout(election));
		var appended = node.addLearner("b").stream().filter(WriteState.class::isInstance).map(WriteState.class::cast)
			.findFirst().orElseThrow();
		var commit = node.onPersistenceSucceeded(appended).stream().filter(WriteState.class::isInstance)
			.map(WriteState.class::cast).findFirst().orElseThrow();
		assertEquals(Set.of("b"), node.membership().effective().learners());
		assertTrue(node.membership().committed().learners().isEmpty());
		node.onPersistenceSucceeded(commit);
		assertEquals(Set.of("b"), node.membership().committed().learners());
		confirm(node, node.removeLearner("b"));
		assertTrue(node.membership().committed().learners().isEmpty());
		assertFalse(node.replication().nextIndex().containsKey("b"));
	}

	@Test void onlyCorrelatedAdmittedLearnerResponseCanAdvanceHigherRetainedTerm() {
		var node = new RaftElection(new RaftIdentity("a", "group"), "cluster", Set.of("a"),
			new RaftPersistentState(new RaftHardState(0, null), List.of(), 0), TIMING, new Random(1));
		var timer = node.start().stream().filter(ScheduleTimer.class::isInstance).map(ScheduleTimer.class::cast)
			.findFirst().orElseThrow();
		confirm(node, node.onTimeout(timer));
		var write = node.addLearner("d").stream().filter(WriteState.class::isInstance).map(WriteState.class::cast)
			.findFirst().orElseThrow();
		var effects = node.onPersistenceSucceeded(write);
		var request = effects.stream().filter(Send.class::isInstance).map(Send.class::cast)
			.filter(send -> send.envelope().to().equals("d")).map(send -> (AppendEntries) send.envelope().message())
			.findFirst().orElseThrow();
		var commit = effects.stream().filter(WriteState.class::isInstance).map(WriteState.class::cast).findFirst().orElseThrow();
		confirm(node, node.onPersistenceSucceeded(commit));
		assertTrue(node.onMessage(new Envelope("group", "cluster", "d", "a",
			new AppendResponse(100, request.requestId() + 1, false, 0, 1))).isEmpty());
		assertEquals(Role.LEADER, node.snapshot().role());
		var demoted = node.onMessage(new Envelope("group", "cluster", "d", "a",
			new AppendResponse(100, request.requestId(), false, 0, 1)));
		assertEquals(Role.FOLLOWER, node.snapshot().role()); assertEquals(100, node.snapshot().hardState().currentTerm());
		assertEquals(1, node.snapshot().durableState().currentTerm());
		assertTrue(demoted.stream().anyMatch(WriteState.class::isInstance));
	}

	private ElectionSimulator simulation(long seed) {
		return new ElectionSimulator(seed, List.of("a", "b", "c", "d", "e"), INITIAL);
	}

	private void confirm(RaftElection node, List<Effect> effects) {
		var pending = new ArrayList<>(effects);
		while (true) {
			var write = pending.stream().filter(WriteState.class::isInstance).map(WriteState.class::cast).findFirst();
			if (write.isEmpty()) return;
			pending = new ArrayList<>(node.onPersistenceSucceeded(write.get()));
		}
	}
}
