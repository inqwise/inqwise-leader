package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RaftSnapshotTest {
	@TempDir Path directory;
	static final Set<String> VOTERS = Set.of("a", "b", "c");
	static final RaftConfiguration CONFIG = RaftConfiguration.stable(VOTERS, Set.of());
	static RaftLogEntry entry(long index, long term) {
		return new RaftLogEntry(index, term, RaftLogEntry.Kind.NO_OP, "");
	}
	static RaftSnapshot checkpoint(long index, long term) { return new RaftSnapshot(index, term, CONFIG, 0, ""); }
	static RaftElection node(RaftPersistentState state) {
		return new RaftElection(new RaftIdentity("a", "group"), "cluster", VOTERS, state,
			new RaftElection.Timing(100, 200, 25), new Random(42));
	}
	static RaftElection.Envelope from(RaftElection.Message message) {
		return new RaftElection.Envelope("group", "cluster", "b", "a", message);
	}
	static <T> T first(List<RaftElection.Effect> effects, Class<T> type) {
		return effects.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
	}
	static List<RaftElection.Effect> confirm(RaftElection node, List<RaftElection.Effect> effects) {
		var all = new ArrayList<>(effects);
		for (int i = 0; i < all.size(); i++) if (all.get(i) instanceof RaftElection.WriteState write) {
			all.addAll(node.onPersistenceSucceeded(write));
		}
		return all;
	}
	static RaftElection.Message reply(List<RaftElection.Effect> effects) {
		return first(effects, RaftElection.Send.class).envelope().message();
	}

	@Test void compactionRetainsAbsoluteTailAndRecoveredElectionUsesCheckpointPosition() {
		var entries = IntStream.rangeClosed(1, 150).mapToObj(index -> entry(index, 2)).toList();
		var state = new RaftPersistentState(new RaftHardState(2, null), entries, 140);
		var node = node(state); node.start();
		var point = node.snapshotPoint();
		assertEquals(140, point.index()); assertFalse(point.containsData());
		var write = first(node.compact(checkpoint(140, 2)), RaftElection.WriteState.class);
		assertEquals(state, node.replication().durableState());
		assertFalse(write.restoreSnapshot());
		node.onPersistenceSucceeded(write);
		var compacted = node.replication().durableState();
		assertEquals(140, compacted.baseIndex()); assertEquals(150, compacted.lastIndex());
		assertEquals(entries.subList(140, 150), compacted.log());
		assertThrows(IllegalStateException.class, () -> node.compact(checkpoint(141, 2)));
		var recovered = node(compacted);
		var timer = first(recovered.start(), RaftElection.ScheduleTimer.class);
		var election = confirm(recovered, recovered.onTimeout(timer));
		assertEquals(new RaftElection.LogPosition(150, 2), ((RaftElection.VoteRequest) reply(election)).lastLog());
	}

	@Test void installWaitsForPersistenceThenReplaysOnlyMatchingRetainedTail() {
		var node = node(new RaftPersistentState(new RaftHardState(3, null),
			List.of(entry(1, 1), entry(2, 2), entry(3, 3)), 1));
		node.start();
		var request = new RaftElection.InstallSnapshot(3, 17, checkpoint(2, 2));
		var effects = node.onMessage(from(request));
		assertTrue(effects.stream().noneMatch(RaftElection.Send.class::isInstance));
		var write = first(effects, RaftElection.WriteState.class);
		assertTrue(write.restoreSnapshot()); assertEquals(1, node.replication().durableState().commitIndex());
		var durable = node.onPersistenceSucceeded(write);
		assertEquals(new RaftElection.SnapshotResponse(3, 17, true, 2), reply(durable));
		assertTrue(durable.stream().noneMatch(RaftElection.Committed.class::isInstance));
		assertEquals(List.of(entry(3, 3)), node.replication().durableState().log());
		var duplicate = node.onMessage(from(request));
		assertTrue(duplicate.stream().noneMatch(RaftElection.WriteState.class::isInstance));
		assertEquals(new RaftElection.SnapshotResponse(3, 17, true, 2), reply(duplicate));
		var committed = confirm(node, node.onMessage(from(new RaftElection.AppendEntries(3, 18,
			new RaftElection.LogPosition(2, 2), List.of(entry(3, 3)), 3))));
		assertEquals(entry(3, 3), first(committed, RaftElection.Committed.class).entry());
		assertEquals(3, node.replication().durableState().commitIndex());
	}

	@Test void installingNewerSnapshotDiscardsConflictingSuffixAndRejectsCommittedConflict() {
		var node = node(new RaftPersistentState(new RaftHardState(3, null),
			List.of(entry(1, 1), entry(2, 1), entry(3, 1)), 1)); node.start();
		confirm(node, node.onMessage(from(new RaftElection.InstallSnapshot(3, 1, checkpoint(2, 2)))));
		assertEquals(List.of(), node.replication().durableState().log());
		assertEquals(2, node.replication().durableState().lastIndex());
		var failed = node.onMessage(from(new RaftElection.InstallSnapshot(3, 2, checkpoint(2, 1))));
		assertEquals(RaftElection.Role.FAILED, node.snapshot().role());
		assertNotNull(first(failed, RaftElection.Failure.class));
	}

	@Test void leaderSendsSnapshotToLaggingFollowerAndRequiresCorrelatedAcknowledgment() {
		var node = node(new RaftPersistentState(new RaftHardState(2, null), List.of(), 100, checkpoint(100, 2)));
		confirm(node, node.onTimeout(first(node.start(), RaftElection.ScheduleTimer.class)));
		var elected = confirm(node, node.onMessage(from(new RaftElection.VoteResponse(3, 3, true))));
		var append = (RaftElection.AppendEntries) elected.stream().filter(RaftElection.Send.class::isInstance)
			.map(RaftElection.Send.class::cast).filter(send -> send.envelope().to().equals("b"))
			.map(send -> send.envelope().message()).findFirst().orElseThrow();
		var transfer = (RaftElection.InstallSnapshot) reply(node.onMessage(from(
			new RaftElection.AppendResponse(3, append.requestId(), false, 0, 1))));
		assertEquals(checkpoint(100, 2), transfer.snapshot());
		node.onMessage(from(new RaftElection.SnapshotResponse(3, transfer.requestId() + 1, true, 100)));
		assertEquals(0L, node.replication().matchIndex().get("b"));
		var accepted = node.onMessage(from(new RaftElection.SnapshotResponse(3, transfer.requestId(), true, 100)));
		assertEquals(100L, node.replication().matchIndex().get("b"));
		var next = (RaftElection.AppendEntries) reply(accepted);
		assertEquals(new RaftElection.LogPosition(100, 2), next.previous());
		assertEquals(List.of(entry(101, 3)), next.entries());
	}

	@Test void jointCheckpointPreservesBothMajoritiesAndFinalConfigurationHistory() throws Exception {
		var joint = new RaftConfiguration(VOTERS, Set.of("a", "d", "e"), Set.of());
		var checkpoint = new RaftSnapshot(5000, 4, joint, 4999, "state");
		var finalEntry = new RaftLogEntry(5001, 4, RaftLogEntry.Kind.CONFIGURATION,
			RaftConfiguration.stable(joint.newVoters(), Set.of()).encode());
		var state = new RaftPersistentState(new RaftHardState(4, null), List.of(finalEntry), 5000, checkpoint);
		var history = RaftConfiguration.history(VOTERS, state.log(), state.commitIndex(), checkpoint);
		assertEquals(joint, history.committed()); assertEquals(joint, history.previous());
		assertEquals(4999, history.committedIndex());
		assertFalse(joint.hasQuorum(Set.of("a", "b"))); assertTrue(joint.hasQuorum(Set.of("a", "b", "d")));
		var encoded = RaftStoreFormat.encode(new RaftStoreFormat.StoredState(new RaftIdentity("a", "group"), state));
		assertEquals(state, RaftStoreFormat.decode(encoded).state());
	}

	@ParameterizedTest @EnumSource(FileRaftStore.WriteStage.class)
	void interruptedCompactionRecoversWholeOldOrWholeNewState(FileRaftStore.WriteStage boundary) throws Exception {
		var before = new RaftPersistentState(new RaftHardState(2, null), List.of(entry(1, 1), entry(2, 2)), 2);
		var after = new RaftPersistentState(before.hardState(), List.of(), 2, checkpoint(2, 2));
		try (var store = FileRaftStore.open(directory, "group", "a")) { store.savePersistentState(before); }
		try (var store = FileRaftStore.open(directory, "group", "a", stage -> {
			if (stage == boundary) throw new IOException("interrupted checkpoint");
		})) { assertThrows(IOException.class, () -> store.savePersistentState(after)); }
		try (var store = FileRaftStore.open(directory, "group", "a")) {
			assertEquals(boundary == FileRaftStore.WriteStage.STAGED ? before : after, store.persistentState());
		}
	}

	@Test void snapshotStorePreservesCommitmentAcrossAppendTruncationAndRestartBeyond4096() throws Exception {
		var snapshot = checkpoint(5000, 2);
		try (var store = FileRaftStore.open(directory, "group", "a")) {
			store.installGenesis(RaftGenesis.create("cluster", "group", null, VOTERS, 3));
			store.savePersistentState(new RaftPersistentState(new RaftHardState(3, null), List.of(entry(5001, 3)), 5000, snapshot));
			store.appendEntries(List.of(entry(5002, 3))); store.truncateSuffix(5002);
			store.saveHardState(new RaftHardState(4, null));
			assertEquals(snapshot, store.persistentState().snapshot());
			assertEquals(List.of(entry(5001, 3)), store.persistentState().log());
			assertThrows(IllegalArgumentException.class, () -> store.truncateSuffix(5000));
			assertThrows(IllegalArgumentException.class, () -> store.savePersistentState(new RaftPersistentState(
				store.hardState(), List.of(entry(5001, 3)), 5000, checkpoint(5000, 1))));
		}
		try (var recovered = FileRaftStore.open(directory, "group", "a")) {
			assertEquals(snapshot, recovered.persistentState().snapshot()); assertEquals(5001, recovered.persistentState().lastIndex());
		}
	}

	@Test void rejectsWrongMembershipCompactionWithoutPoisoningStore() throws Exception {
		try (var store = FileRaftStore.open(directory, "group", "a")) {
			store.installGenesis(RaftGenesis.create("cluster", "group", null, VOTERS, 3));
			store.savePersistentState(new RaftPersistentState(new RaftHardState(1, null), List.of(entry(1, 1)), 1));
			var incorrect = new RaftSnapshot(1, 1, RaftConfiguration.stable(Set.of("a"), Set.of()), 1, "");
			assertThrows(IllegalArgumentException.class, () -> store.savePersistentState(new RaftPersistentState(
				store.hardState(), List.of(), 1, incorrect)));
			store.savePersistentState(new RaftPersistentState(store.hardState(), List.of(), 1, checkpoint(1, 1)));
		}
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(longs = {7, 21, 43, 87, 219})
	void compactedClusterCatchesUpAfterLossDuplicatesAndRestart(long seed) {
		var simulation = new ElectionSimulator(seed, List.of("a", "b", "c"));
		simulation.advance(2000); String leader = simulation.soleLeader();
		String lagging = List.of("a", "b", "c").stream().filter(member -> !member.equals(leader)).findFirst().orElseThrow();
		simulation.isolated.add(lagging); simulation.crash(lagging);
		for (int i = 0; i < 5; i++) { simulation.propose(leader, "before-checkpoint-" + i); simulation.advance(100); }
		simulation.compact(leader); simulation.advance(100);
		long boundary = simulation.disks.get(leader).baseIndex(); assertTrue(boundary > 0);
		simulation.propose(leader, "after-checkpoint"); simulation.advance(100);
		simulation.dropPercent = 15; simulation.duplicateMessages = true; simulation.maxNetworkDelay = 20;
		simulation.restart(lagging); simulation.isolated.remove(lagging); simulation.advance(1500);
		assertTrue(simulation.disks.get(lagging).baseIndex() >= boundary);
		assertTrue(simulation.disks.get(lagging).commitIndex() > boundary);
		simulation.dropPercent = 0; simulation.duplicateMessages = false;
		simulation.restart(leader); simulation.advance(2000);
		String recovered = simulation.soleLeader(); simulation.propose(recovered, "after-restart"); simulation.advance(200);
		assertEquals("after-restart", simulation.disks.get(recovered).log().getLast().data());
	}

	@Test void versionFourRemainsReadableAndSnapshotCorruptionFailsChecksum() throws Exception {
		var state = new RaftPersistentState(new RaftHardState(1, null), List.of(entry(1, 1)), 1);
		var stored = new RaftStoreFormat.StoredState(new RaftIdentity("a", "group"), state);
		var latest = RaftStoreFormat.encode(stored);
		var old = java.util.Arrays.copyOf(latest, latest.length - 1); int payload = old.length - 32;
		ByteBuffer.wrap(old).putInt(4, 4);
		System.arraycopy(MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOf(old, payload)), 0, old, payload, 32);
		assertEquals(state, RaftStoreFormat.decode(old).state());
		var compacted = RaftStoreFormat.encode(new RaftStoreFormat.StoredState(stored.identity(),
			new RaftPersistentState(state.hardState(), List.of(), 1, new RaftSnapshot(1, 1, CONFIG, 0, "application"))));
		compacted[compacted.length - 33] ^= 1;
		assertThrows(IOException.class, () -> RaftStoreFormat.decode(compacted));
		assertThrows(IllegalArgumentException.class, () -> new RaftSnapshot(1, 1, CONFIG, 0, "\ud800"));
		assertThrows(IllegalArgumentException.class, () -> new RaftSnapshot(1, 1, CONFIG, 0,
			"x".repeat(RaftSnapshot.MAX_DATA_BYTES + 1)));
	}
}
