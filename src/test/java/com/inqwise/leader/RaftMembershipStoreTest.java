package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import io.vertx.core.Vertx;

class RaftMembershipStoreTest {
	@TempDir Path directory;
	RaftGenesis genesis() { return RaftGenesis.create("cluster", "group", "node-a", Set.of("node-a"), 1); }

	@Test void admittedLearnerAndPromotedLogRecoverAcrossBothStores() throws Exception {
		var vertx = Vertx.vertx();
		try {
			var store = VertxRaftStore.create(vertx, directory, "group", "node-b"); await(store.open());
			assertThrows(Exception.class, () -> await(store.installGenesis(genesis())));
			await(store.installLearnerGenesis(genesis()));
			var learner = RaftConfiguration.stable(Set.of("node-a"), Set.of("node-b"));
			var joint = new RaftConfiguration(Set.of("node-a"), Set.of("node-a", "node-b"), Set.of());
			var stable = RaftConfiguration.stable(Set.of("node-a", "node-b"), Set.of());
			var log = List.of(new RaftLogEntry(1, 1, RaftLogEntry.Kind.CONFIGURATION, learner.encode()),
				new RaftLogEntry(2, 1, RaftLogEntry.Kind.CONFIGURATION, joint.encode()),
				new RaftLogEntry(3, 1, RaftLogEntry.Kind.CONFIGURATION, stable.encode()));
			await(store.savePersistentState(new RaftPersistentState(new RaftHardState(1, null), log, 3)));
			await(store.installLearnerGenesis(genesis())); // Identical admission is harmless after promotion.
			await(store.close());
			try (var recovered = FileRaftStore.open(directory, "group", "node-b")) {
				assertTrue(recovered.bootstrapState().learnerAdmission());
				assertEquals(stable, RaftConfiguration.history(genesis().voters(), recovered.persistentState().log(), 3).effective());
				assertThrows(IllegalArgumentException.class, () -> recovered.savePersistentState(new RaftPersistentState(
					new RaftHardState(1, null), log.subList(0, 2), 2)));
			}
		} finally { await(vertx.close()); }
	}

	@Test void invalidDirectVoterChangeCannotBePersisted() throws Exception {
		try (var store = FileRaftStore.open(directory, "group", "node-a")) {
			store.installGenesis(genesis());
			var illegal = RaftConfiguration.stable(Set.of("node-b"), Set.of());
			assertThrows(IllegalArgumentException.class, () -> store.savePersistentState(new RaftPersistentState(
				new RaftHardState(1, null), List.of(new RaftLogEntry(1, 1, RaftLogEntry.Kind.CONFIGURATION, illegal.encode())), 0)));
			assertTrue(store.persistentState().log().isEmpty());
			store.saveHardState(new RaftHardState(1, null)); // Validation failure does not poison storage.
		}
	}

	@Test void versionThreeBindingsUpgradeWithoutChangingGenesisOrVotingAdmission() throws Exception {
		try (var store = FileRaftStore.open(directory, "group", "node-a")) { store.installGenesis(genesis()); }
		Path path = directory.resolve("raft-state.bin");
		byte[] bytes = Files.readAllBytes(path);
		byte[] old = Arrays.copyOf(bytes, bytes.length - 32 - 2); // Snapshot presence and learner-admission flags.
		ByteBuffer.wrap(old).putInt(4, 3);
		Files.write(path, ByteBuffer.allocate(old.length + 32).put(old)
			.put(MessageDigest.getInstance("SHA-256").digest(old)).array());
		try (var store = FileRaftStore.open(directory, "group", "node-a")) {
			assertEquals(new RaftBootstrapState(genesis(), true), store.bootstrapState());
			store.saveHardState(new RaftHardState(1, null));
		}
		assertEquals(5, ByteBuffer.wrap(Files.readAllBytes(path)).getInt(4));
	}
	static java.util.stream.Stream<Arguments> transitionFailures() {
		return Arrays.stream(FileRaftStore.WriteStage.values()).flatMap(stage -> java.util.stream.Stream.of(
			Arguments.of(stage, false), Arguments.of(stage, true)));
	}

	@ParameterizedTest @MethodSource("transitionFailures")
	void transitionWriteFailuresRecoverWholeJointOrFinalState(FileRaftStore.WriteStage stage, boolean finalizing)
		throws Exception {
		var learner = new RaftLogEntry(1, 1, RaftLogEntry.Kind.CONFIGURATION,
			RaftConfiguration.stable(Set.of("node-a"), Set.of("node-b")).encode());
		var joint = new RaftLogEntry(2, 1, RaftLogEntry.Kind.CONFIGURATION,
			new RaftConfiguration(Set.of("node-a"), Set.of("node-a", "node-b"), Set.of()).encode());
		var stable = new RaftLogEntry(3, 1, RaftLogEntry.Kind.CONFIGURATION,
			RaftConfiguration.stable(Set.of("node-a", "node-b"), Set.of()).encode());
		var before = new RaftPersistentState(new RaftHardState(1, "node-a"),
			finalizing ? List.of(learner, joint) : List.of(learner), finalizing ? 2 : 1);
		var after = new RaftPersistentState(before.hardState(), finalizing ? List.of(learner, joint, stable)
			: List.of(learner, joint), before.commitIndex());
		try (var store = FileRaftStore.open(directory, "group", "node-a")) {
			store.installGenesis(genesis()); store.savePersistentState(before);
		}
		try (var interrupted = FileRaftStore.open(directory, "group", "node-a", boundary -> {
			if (boundary == stage) throw new java.io.IOException("interrupted membership write");
		})) {
			assertThrows(java.io.IOException.class, () -> interrupted.savePersistentState(after));
			assertThrows(java.io.IOException.class, interrupted::persistentState);
		}
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			assertEquals(stage == FileRaftStore.WriteStage.STAGED ? before : after, recovered.persistentState());
			assertEquals(genesis(), recovered.bootstrapState().genesis());
		}
	}

}
