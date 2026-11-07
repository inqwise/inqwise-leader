package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

class DataDirectoriesTest {
	@TempDir Path directory;
	final Vertx vertx = Vertx.vertx();
	@AfterEach void shutdown() throws Exception { await(vertx.close()); }
	LeaderOptions options(String id) {
		return LeaderOptions.builder().groupName("group").bootstrapAuthorityId("node-a").nodeId(id).build();
	}
	Path resolve(String id) throws Exception {
		return await(DataDirectories.resolve(vertx.getOrCreateContext(), options(id), directory));
	}
	void initialize(Path path, String id) throws Exception {
		var store = VertxRaftStore.create(vertx, path, "group", id);
		try { await(store.open()); } finally { await(store.close()); }
	}

	@Test void distinctNodeDirectoriesAreAutomaticAndRecoverableWithoutExplicitIdentity() throws Exception {
		Path a = resolve("node-a"); assertEquals(directory.resolve("nodes"), a.getParent());
		assertTrue(a.getFileName().toString().matches("[a-f0-9]{64}"));
		initialize(a, "node-a"); assertEquals(a, resolve("node-a"));
		assertEquals(a.toRealPath(), resolve(null).toRealPath());
		Path b = resolve("node-b"); assertNotEquals(a, b); initialize(b, "node-b");
		assertThrows(Exception.class, () -> resolve(null));
		assertEquals(a, resolve("node-a")); assertEquals(b, resolve("node-b"));
	}

	@Test void legacyIdentityRemainsAccessibleWhileAnotherNodeGetsItsOwnDirectory() throws Exception {
		initialize(directory, "node-a");
		var genesis = RaftGenesis.create("retained-cluster", "group", "node-a", java.util.Set.of("node-a"), 1);
		var original = VertxRaftStore.create(vertx, directory, "group", "node-a");
		try {
			await(original.open()); await(original.installGenesis(genesis));
			await(original.saveHardState(new RaftHardState(3, "node-a")));
		} finally { await(original.close()); }
		assertEquals(directory, resolve("node-a")); assertEquals(directory, resolve(null));
		assertEquals(directory.resolve("nodes"), resolve("node-b").getParent());
		var reopened = VertxRaftStore.create(vertx, resolve("node-a"), "group", "node-a");
		try {
			await(reopened.open()); assertEquals("node-a", await(reopened.identity()).nodeId());
			assertEquals(genesis, await(reopened.bootstrapState()).genesis());
			assertEquals(3, await(reopened.persistentState()).hardState().currentTerm());
		}
		finally { await(reopened.close()); }
	}

	@Test void corruptIncompleteOrAmbiguousStateNeverSelectsAFreshDirectory() throws Exception {
		initialize(directory, "node-a"); initialize(LeaderOptions.nodeDirectory(directory, "node-a"), "node-a");
		assertThrows(Exception.class, () -> resolve("node-a"));
		assertThrows(Exception.class, () -> resolve(null));
		await(vertx.fileSystem().writeFile(directory.resolve("raft-state.bin").toString(), Buffer.buffer("corrupt")));
		assertThrows(Exception.class, () -> resolve("node-b"));
		await(vertx.fileSystem().delete(directory.resolve("raft-state.bin").toString()));
		assertThrows(Exception.class, () -> resolve("node-b"));
	}

	@Test void generatedIdentityReopensItsSlotAndStoreFileNamesAreSafeNodeIds() throws Exception {
		assertEquals(directory, resolve(null)); initialize(directory, null);
		assertEquals(directory, resolve(null));
		for (String id : List.of("raft-state.bin", "initialized", "store.lock", "nodes", "Node-A", "node-a")) {
			Path path = resolve(id); initialize(path, id); assertEquals(path, resolve(id));
		}
	}

	@Test void explicitOverrideBypassesAutomaticSelectionWithoutCreatingAnything() throws Exception {
		Path custom = directory.resolve("custom");
		var options = options("node-a").toBuilder().dataDirectory(custom.toString()).build();
		assertEquals(custom, await(DataDirectories.resolve(vertx.getOrCreateContext(), options, directory)));
		assertFalse(await(vertx.fileSystem().exists(custom.toString())));
	}
}
