package com.inqwise.leader;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RaftLogStoreTest {
	@TempDir
	Path directory;

	private RaftLogEntry entry(long index, long term, String data) {
		return new RaftLogEntry(index, term, RaftLogEntry.Kind.DATA, data);
	}

	static Stream<Arguments> interruptedReplacements() {
		return java.util.Arrays.stream(FileRaftStore.WriteStage.values())
			.flatMap(stage -> Stream.of(Arguments.of(stage, false), Arguments.of(stage, true)));
	}

	@ParameterizedTest(name = "failure at {0}, truncate={1}")
	@MethodSource("interruptedReplacements")
	void testDurabilityBoundaryFailuresRecoverACompleteSnapshot(FileRaftStore.WriteStage stage, boolean truncate)
		throws Exception {
		var before = new RaftPersistentState(new RaftHardState(2, "node-a"),
			List.of(entry(1, 1, "committed"), entry(2, 2, "uncommitted")), 1);
		try (var store = FileRaftStore.open(directory, "group", "node-a")) {
			store.savePersistentState(before);
		}
		RaftPersistentState after = new RaftPersistentState(before.hardState(), truncate
			? List.of(entry(1, 1, "committed"))
			: List.of(entry(1, 1, "committed"), entry(2, 2, "uncommitted"), entry(3, 2, "appended")), 1);
		var reached = new AtomicBoolean();
		try (var store = FileRaftStore.open(directory, "group", "node-a", boundary -> {
			if (boundary == stage) {
				reached.set(true);
				throw new IOException("simulated write interruption at " + boundary);
			}
		})) {
			Assertions.assertThrows(IOException.class, () -> store.savePersistentState(after));
			Assertions.assertTrue(reached.get());
			Assertions.assertThrows(IOException.class, store::persistentState);
			Assertions.assertThrows(IOException.class, () -> store.savePersistentState(before));
		}
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			// This models exceptions between actual I/O steps, not physical power loss.
			Assertions.assertEquals(stage == FileRaftStore.WriteStage.STAGED ? before : after,
				recovered.persistentState());
			Assertions.assertEquals(before.log().getFirst(), recovered.persistentState().log().getFirst());
		}
	}

	@Test
	void testAppendTruncateAndReplaceRecoverAsOneState() throws Exception {
		RaftPersistentState expected;
		try (var store = FileRaftStore.open(directory, "group", "node-a")) {
			store.saveHardState(new RaftHardState(2, "node-a"));
			store.appendEntries(List.of(entry(1, 1, "保留"), entry(2, 2, "discard"), entry(3, 2, "discard-too")));
			store.savePersistentState(new RaftPersistentState(store.hardState(), store.persistentState().log(), 1));
			store.truncateSuffix(2);
			store.saveHardState(new RaftHardState(3, null));
			store.appendEntries(List.of(entry(2, 3, "replacement-😀")));
			store.truncateSuffix(3);
			expected = new RaftPersistentState(new RaftHardState(3, null),
				List.of(entry(1, 1, "保留"), entry(2, 3, "replacement-😀")), 1);
			Assertions.assertEquals(expected, store.persistentState());
			Assertions.assertThrows(UnsupportedOperationException.class,
				() -> store.persistentState().log().clear());
		}
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			Assertions.assertEquals(expected, recovered.persistentState());
		}
	}

	@Test
	void testCommittedPrefixAndCommitIndexCannotBeRewritten() throws Exception {
		try (var store = FileRaftStore.open(directory, "group")) {
			var committed = new RaftPersistentState(new RaftHardState(2, null), List.of(entry(1, 1, "fixed")), 1);
			store.savePersistentState(committed);
			Assertions.assertAll(
				() -> Assertions.assertThrows(IllegalArgumentException.class, () -> store.truncateSuffix(1)),
				() -> Assertions.assertThrows(IllegalArgumentException.class,
					() -> store.savePersistentState(new RaftPersistentState(store.hardState(), committed.log(), 0))),
				() -> Assertions.assertThrows(IllegalArgumentException.class,
					() -> store.savePersistentState(new RaftPersistentState(store.hardState(),
						List.of(entry(1, 2, "different")), 1))));
			Assertions.assertEquals(committed, store.persistentState());
		}
	}

	@Test
	void testInvalidLogOperationsLeaveStoreUsable() throws Exception {
		try (var store = FileRaftStore.open(directory, "group")) {
			store.saveHardState(new RaftHardState(2, null));
			store.appendEntries(List.of(entry(1, 1, "one")));
			RaftPersistentState before = store.persistentState();
			Assertions.assertAll(
				() -> Assertions.assertThrows(IllegalArgumentException.class,
					() -> store.appendEntries(List.of(entry(3, 2, "gap")))),
				() -> Assertions.assertThrows(IllegalArgumentException.class,
					() -> store.appendEntries(List.of(entry(2, 3, "future-term")))),
				() -> Assertions.assertThrows(IllegalArgumentException.class, () -> store.truncateSuffix(0)),
				() -> Assertions.assertThrows(IllegalArgumentException.class, () -> store.truncateSuffix(3)));
			Assertions.assertEquals(before, store.persistentState());
			store.appendEntries(List.of(entry(2, 2, "valid")));
		}
	}

	@Test
	void testFailedReplacementRecoversAcknowledgedLogAndCommit() throws Exception {
		RaftPersistentState acknowledged = new RaftPersistentState(new RaftHardState(2, "node-a"),
			List.of(entry(1, 1, "committed"), entry(2, 2, "uncommitted")), 1);
		try (var store = FileRaftStore.open(directory, "group", "node-a")) {
			store.savePersistentState(acknowledged);
			Files.createDirectory(directory.resolve("raft-state.pending"));
			Assertions.assertThrows(IOException.class, () -> store.truncateSuffix(2));
			Assertions.assertThrows(IOException.class, store::persistentState);
		}
		Files.delete(directory.resolve("raft-state.pending"));
		Files.write(directory.resolve("raft-state.pending"), new byte[] { 0, 1, 2 });
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			Assertions.assertEquals(acknowledged, recovered.persistentState());
			recovered.truncateSuffix(2);
		}
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			Assertions.assertEquals(List.of(entry(1, 1, "committed")), recovered.persistentState().log());
			Assertions.assertEquals(1, recovered.persistentState().commitIndex());
		}
	}

	@Test
	void testVersionOneStateUpgradesWithoutLosingIdentityOrVote() throws Exception {
		try (var store = FileRaftStore.open(directory, "group", "node-a")) {
			Assertions.assertEquals("node-a", store.identity().nodeId());
		}
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			output.writeInt(0x494C5253);
			output.writeInt(1);
			output.writeUTF("node-a");
			output.writeUTF("group");
			output.writeLong(3);
			output.writeBoolean(true);
			output.writeUTF("node-b");
			output.flush();
			output.write(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
		}
		Files.write(directory.resolve("raft-state.bin"), bytes.toByteArray());
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			Assertions.assertEquals(new RaftHardState(3, "node-b"), recovered.hardState());
			Assertions.assertTrue(recovered.persistentState().log().isEmpty());
			recovered.appendEntries(List.of(entry(1, 3, "first-log-entry")));
		}
		Assertions.assertEquals(5, ByteBuffer.wrap(Files.readAllBytes(directory.resolve("raft-state.bin"))).getInt(4));
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			Assertions.assertEquals(new RaftHardState(3, "node-b"), recovered.hardState());
			Assertions.assertEquals(List.of(entry(1, 3, "first-log-entry")), recovered.persistentState().log());
		}
	}

	@Test
	void testAggregatePayloadLimitIsEnforced() {
		String payload = "x".repeat(RaftLogEntry.MAX_DATA_BYTES);
		int count = RaftPersistentState.MAX_LOG_DATA_BYTES / RaftLogEntry.MAX_DATA_BYTES + 1;
		List<RaftLogEntry> entries = java.util.stream.LongStream.rangeClosed(1, count)
			.mapToObj(index -> entry(index, 1, payload)).toList();
		Assertions.assertThrows(IllegalArgumentException.class,
			() -> new RaftPersistentState(new RaftHardState(1, null), entries, 0));
	}

	@Test
	void testEntryAndPersistentLogBounds() {
		Assertions.assertAll(
			() -> Assertions.assertThrows(IllegalArgumentException.class, () -> entry(0, 1, "zero-index")),
			() -> Assertions.assertThrows(IllegalArgumentException.class, () -> entry(1, 0, "zero-term")),
			() -> Assertions.assertThrows(IllegalArgumentException.class, () -> entry(1, 1, "\ud800")),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> entry(1, 1, "x".repeat(RaftLogEntry.MAX_DATA_BYTES + 1))),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> new RaftLogEntry(1, 1, RaftLogEntry.Kind.NO_OP, "not-empty")),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> new RaftPersistentState(new RaftHardState(2, null),
					List.of(entry(1, 2, "one"), entry(2, 1, "term-regression")), 0)),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> new RaftPersistentState(new RaftHardState(1, null), List.of(entry(1, 1, "one")), 2)),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> new RaftPersistentState(new RaftHardState(1, null),
					java.util.Collections.nCopies(RaftPersistentState.MAX_LOG_ENTRIES + 1, entry(1, 1, "one")), 0)));
	}
}
