package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

class RaftBootstrapStoreTest {
	@TempDir Path directory;
	Vertx vertx;
	static final Set<String> VOTERS = Set.of("node-a", "node-b", "node-c");
	@BeforeEach void setup() { vertx = Vertx.vertx(); }
	@AfterEach void shutdown() throws Exception { await(vertx.close()); }
	VertxRaftStore store(String id) { return VertxRaftStore.create(vertx, directory, "group", id); }
	RaftGenesis genesis(String cluster) { return RaftGenesis.create(cluster, "group", "node-a", VOTERS, 3); }

	@Test void canonicalDigestValidatesEveryFieldAndVoterOrder() {
		var first = genesis("cluster");
		var reordered = RaftGenesis.create("cluster", "group", "node-a",
			new java.util.LinkedHashSet<>(List.of("node-c", "node-a", "node-b")), 3);
		assertEquals(first, reordered);
		assertThrows(IllegalArgumentException.class, () -> new RaftGenesis("other", first.groupName(), first.authorityId(),
			first.voters(), first.targetVoterCount(), first.digest()));
		assertThrows(IllegalArgumentException.class, () -> RaftGenesis.create("cluster", "group", "node-a", VOTERS, 5));
		assertThrows(IllegalArgumentException.class,
			() -> RaftGenesis.create("cluster", "group", "node-a", Set.of("node-a", "node-b"), 2));
	}

	@Test void authorityRecordAndInstallationSurviveBothStoreImplementations() throws Exception {
		var store = store("node-a"); await(store.open());
		assertThrows(Exception.class, () -> await(store.createGenesis(Set.of("node-a"), 3)));
		var original = await(store.createGenesis(VOTERS, 3));
		assertFalse(await(store.bootstrapState()).installed());
		assertThrows(Exception.class, () -> await(store.saveHardState(new RaftHardState(1, null))));
		assertEquals(original, await(store.createGenesis(Set.of("node-a", "node-z"), 1)));
		await(store.close());
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			assertEquals(original, recovered.bootstrapState().genesis());
			recovered.installGenesis(original);
			recovered.saveHardState(new RaftHardState(1, "node-a"));
			recovered.installGenesis(original); // Idempotent after protocol history exists.
		}
		var recovered = store("node-a"); await(recovered.open());
		assertEquals(new RaftBootstrapState(original, true), await(recovered.bootstrapState()));
		assertEquals(1, await(recovered.persistentState()).hardState().currentTerm());
		assertThrows(Exception.class, () -> await(recovered.installGenesis(genesis("foreign"))));
		await(recovered.close());
	}

	@Test void concurrentFormationReturnsOneRecordAndWaitsForDurability() throws Exception {
		Promise<Void> gate = Promise.promise(); CountDownLatch reached = new CountDownLatch(1);
		AtomicBoolean armed = new AtomicBoolean();
		var store = new VertxRaftStore(vertx.getOrCreateContext(), directory, "group", "node-a", 5000, stage -> {
			if (armed.get() && stage == VertxRaftStore.WriteStage.STAGED) { reached.countDown(); return gate.future(); }
			return Future.succeededFuture();
		});
		await(store.open()); armed.set(true);
		var first = store.createGenesis(VOTERS, 3);
		assertTrue(reached.await(5, TimeUnit.SECONDS));
		var second = store.createGenesis(Set.of("node-a", "node-d", "node-e"), 3);
		assertFalse(first.isComplete()); assertFalse(second.isComplete());
		gate.complete();
		assertEquals(await(first), await(second));
		await(store.close());
	}

	@Test void installationRejectsNonSelectedVotersAndUnboundHistory() throws Exception {
		var outsider = store("node-z"); await(outsider.open());
		assertThrows(Exception.class, () -> await(outsider.installGenesis(genesis("cluster"))));
		assertEquals(RaftBootstrapState.UNASSIGNED, await(outsider.bootstrapState()));
		await(outsider.close());
		var used = VertxRaftStore.create(vertx, directory.resolve("used"), "group", "node-a"); await(used.open());
		await(used.saveHardState(new RaftHardState(1, null)));
		assertThrows(Exception.class, () -> await(used.installGenesis(genesis("cluster"))));
		assertThrows(Exception.class, () -> await(used.createGenesis(VOTERS, 3)));
		await(used.close());
	}

	static Stream<Arguments> failures() {
		return Arrays.stream(VertxRaftStore.WriteStage.values()).flatMap(stage ->
			Stream.of(Arguments.of(stage, false), Arguments.of(stage, true)));
	}

	@ParameterizedTest @MethodSource("failures")
	void writeBoundaryFailureRecoversWholeBootstrapState(VertxRaftStore.WriteStage boundary, boolean installing)
		throws Exception {
		RaftGenesis original;
		try (var baseline = FileRaftStore.open(directory, "group", "node-a")) {
			original = installing ? baseline.createGenesis(VOTERS, 3) : null;
		}
		var store = new VertxRaftStore(vertx.getOrCreateContext(), directory, "group", "node-a", 5000,
			stage -> stage == boundary ? Future.failedFuture(new IOException("interrupted")) : Future.succeededFuture());
		await(store.open());
		assertThrows(Exception.class, () -> await(installing ? store.installGenesis(original) : store.createGenesis(VOTERS, 3).map(v -> (Void) null)));
		assertThrows(Exception.class, () -> await(store.bootstrapState()));
		await(store.close());
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			var state = recovered.bootstrapState();
			if (boundary == VertxRaftStore.WriteStage.STAGED) {
				assertEquals(original, state.genesis()); assertFalse(state.installed());
			} else {
				assertNotNull(state.genesis()); assertEquals(VOTERS, state.genesis().voters());
				assertEquals(installing, state.installed());
				if (installing) assertEquals(original, state.genesis());
			}
		}
	}

	@Test void versionTwoUnboundHistoryIsReadableButCannotBeSilentlyAssigned() throws Exception {
		try (var store = FileRaftStore.open(directory, "group", "node-a")) {
			store.saveHardState(new RaftHardState(2, "node-a"));
		}
		Path file = directory.resolve("raft-state.bin");
		byte[] encoded = Files.readAllBytes(file);
		byte[] payload = Arrays.copyOf(encoded, encoded.length - 32 - 2); // v3's absent-bootstrap boolean.
		ByteBuffer.wrap(payload).putInt(4, 2);
		Files.write(file, ByteBuffer.allocate(payload.length + 32).put(payload)
			.put(MessageDigest.getInstance("SHA-256").digest(payload)).array());
		var recovered = store("node-a"); await(recovered.open());
		assertEquals(RaftBootstrapState.UNASSIGNED, await(recovered.bootstrapState()));
		assertThrows(Exception.class, () -> await(recovered.installGenesis(genesis("cluster"))));
		await(recovered.close());
	}

	@Test void genesisDigestIsCheckedEvenWithValidOuterChecksum() throws Exception {
		try (var baseline = FileRaftStore.open(directory, "group", "node-a")) {
			baseline.installGenesis(genesis("cluster"));
		}
		Path file = directory.resolve("raft-state.bin");
		byte[] bytes = Files.readAllBytes(file);
		byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
		int digestOffset = payload.length - 3 - 64;
		payload[digestOffset] = payload[digestOffset] == 'a' ? (byte) 'b' : (byte) 'a';
		Files.write(file, ByteBuffer.allocate(payload.length + 32).put(payload)
			.put(MessageDigest.getInstance("SHA-256").digest(payload)).array());
		assertThrows(IOException.class, () -> FileRaftStore.open(directory, "group", "node-a"));
	}
	@ParameterizedTest @ValueSource(ints = {1, 3, 7, 9})
	void configuredOddTargetsSelectExactlyThatManyVoters(int target) throws Exception {
		var store = store("node-a"); await(store.open());
		var eligible = new java.util.TreeSet<String>();
		for (int i = 0; i < target + 2; i++) eligible.add("node-" + i);
		var genesis = await(store.createGenesis(eligible, target));
		assertEquals(target, genesis.voters().size()); assertEquals(target, genesis.targetVoterCount());
		assertTrue(eligible.containsAll(genesis.voters()));
		assertEquals(genesis, await(store.createGenesis(Set.of("new-node"), 1)));
		await(store.close());
	}

	@Test void knownClusterCandidatePreventsNewFormationAndAuthorityChangesFailClosed() throws Exception {
		var store = store("node-a"); await(store.open());
		var controller = new RaftBootstrap(store, "node-a");
		var assigned = new DiscoveredPeer(new RaftIdentity("node-b", "group"), "existing",
			new NodeEndpoint("127.0.0.1", 8080, "node-b"));
		assertThrows(Exception.class, () -> await(controller.form(List.of(assigned))));
		assertEquals(RaftBootstrapState.UNASSIGNED, await(store.bootstrapState()));
		await(store.createGenesis(VOTERS, 3));
		assertThrows(Exception.class, () -> await(new RaftBootstrap(store, "node-z").state()));
		await(store.close());
	}

}
