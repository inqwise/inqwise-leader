package com.inqwise.leader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FileRaftStoreTest {
	@TempDir
	Path temporary;

	@Test
	void testGeneratedIdentitySurvivesRestart() throws Exception {
		Path directory = temporary.resolve("nested/node-a");
		RaftIdentity identity;
		try (var store = FileRaftStore.open(directory, "scheduled-job")) {
			identity = store.identity();
			Assertions.assertDoesNotThrow(() -> UUID.fromString(identity.nodeId()));
			Assertions.assertEquals("scheduled-job", identity.groupName());
			Assertions.assertEquals(new RaftHardState(0, null), store.hardState());
			store.saveHardState(new RaftHardState(12, "candidate-b"));
		}
		try (var store = FileRaftStore.open(directory, "scheduled-job")) {
			Assertions.assertEquals(identity, store.identity());
			Assertions.assertEquals(new RaftHardState(12, "candidate-b"), store.hardState());
			Assertions.assertThrows(IllegalArgumentException.class,
				() -> store.saveHardState(new RaftHardState(12, "candidate-c")));
		}
	}

	@Test
	void testExplicitIdentityCanBeRecoveredWithoutConfiguration() throws Exception {
		try (var store = FileRaftStore.open(temporary, "group", "node-a")) {
			Assertions.assertEquals(new RaftIdentity("node-a", "group"), store.identity());
		}
		try (var store = FileRaftStore.open(temporary, "group")) {
			Assertions.assertEquals("node-a", store.identity().nodeId());
		}
	}

	@ParameterizedTest
	@CsvSource({ "group,node-b", "another-group,node-a" })
	void testConfigurationMismatchDoesNotRewriteState(String group, String node) throws Exception {
		try (var store = FileRaftStore.open(temporary, "group", "node-a")) {
			store.saveHardState(new RaftHardState(3, "node-a"));
		}
		byte[] before = Files.readAllBytes(temporary.resolve("raft-state.bin"));
		Assertions.assertThrows(IOException.class, () -> FileRaftStore.open(temporary, group, node));
		Assertions.assertArrayEquals(before, Files.readAllBytes(temporary.resolve("raft-state.bin")));
		try (var store = FileRaftStore.open(temporary, "group", "node-a")) {
			Assertions.assertEquals(new RaftHardState(3, "node-a"), store.hardState());
		}
	}

	@Test
	void testTermAndVoteTransitionRules() throws Exception {
		try (var store = FileRaftStore.open(temporary, "group")) {
			store.saveHardState(new RaftHardState(1, null));
			store.saveHardState(new RaftHardState(1, "candidate-a"));
			store.saveHardState(new RaftHardState(1, "candidate-a"));
			Assertions.assertThrows(IllegalArgumentException.class,
				() -> store.saveHardState(new RaftHardState(0, null)));
			Assertions.assertThrows(IllegalArgumentException.class,
				() -> store.saveHardState(new RaftHardState(1, null)));
			Assertions.assertThrows(IllegalArgumentException.class,
				() -> store.saveHardState(new RaftHardState(1, "candidate-b")));
			Assertions.assertEquals(new RaftHardState(1, "candidate-a"), store.hardState());
			store.saveHardState(new RaftHardState(2, null));
			store.saveHardState(new RaftHardState(2, "candidate-b"));
		}
		try (var store = FileRaftStore.open(temporary, "group")) {
			Assertions.assertEquals(new RaftHardState(2, "candidate-b"), store.hardState());
		}
	}

	@Test
	void testExclusiveOwnershipAndClose() throws Exception {
		var store = FileRaftStore.open(temporary, "group");
		try {
			Assertions.assertThrows(IOException.class, () -> FileRaftStore.open(temporary, "group"));
		} finally {
			store.close();
		}
		Assertions.assertDoesNotThrow(store::close);
		Assertions.assertThrows(IOException.class, store::identity);
		Assertions.assertThrows(IOException.class, store::hardState);
		Assertions.assertThrows(IOException.class, () -> store.saveHardState(new RaftHardState(1, null)));
		try (var reopened = FileRaftStore.open(temporary, "group")) {
			Assertions.assertEquals(new RaftHardState(0, null), reopened.hardState());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "truncated", "checksum", "oversized", "version", "negative-term", "trailing" })
	void testCorruptStateFailsClosed(String corruption) throws Exception {
		try (var store = FileRaftStore.open(temporary, "group", "node-a")) {
			Assertions.assertEquals("node-a", store.identity().nodeId());
		}
		Path path = temporary.resolve("raft-state.bin");
		byte[] original = Files.readAllBytes(path);
		byte[] corrupted;
		if (corruption.equals("truncated")) {
			corrupted = Arrays.copyOf(original, 10);
		} else if (corruption.equals("checksum")) {
			corrupted = original.clone();
			corrupted[10] ^= 1;
		} else if (corruption.equals("oversized")) {
			corrupted = new byte[16 * 1024 * 1024 + 1];
		} else {
			byte[] payload = Arrays.copyOf(original, original.length - 32);
			switch (corruption) {
				case "version" -> ByteBuffer.wrap(payload).putInt(4, 999);
				case "negative-term" -> {
					var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload));
					input.readInt();
					input.readInt();
					input.readUTF();
					input.readUTF();
					ByteBuffer.wrap(payload).putLong(payload.length - input.available(), -1);
				}
				case "trailing" -> payload = Arrays.copyOf(payload, payload.length + 1);
				default -> throw new IllegalArgumentException(corruption);
			}
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
			corrupted = ByteBuffer.allocate(payload.length + digest.length).put(payload).put(digest).array();
		}
		Files.write(path, corrupted);
		Assertions.assertThrows(IOException.class, () -> FileRaftStore.open(temporary, "group"));
		Assertions.assertArrayEquals(corrupted, Files.readAllBytes(path));
		// Restoring valid bytes also verifies that a failed open released its file lock.
		Files.write(path, original);
		try (var recovered = FileRaftStore.open(temporary, "group")) {
			Assertions.assertEquals("node-a", recovered.identity().nodeId());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "state", "marker", "corrupt-marker" })
	void testMissingDurableFilesNeverRegenerateIdentity(String missing) throws Exception {
		try (var store = FileRaftStore.open(temporary, "group")) {
			store.saveHardState(new RaftHardState(5, "node-b"));
		}
		if (missing.equals("corrupt-marker")) {
			Files.writeString(temporary.resolve("initialized"), "invalid");
		} else {
			Files.delete(temporary.resolve(missing.equals("state") ? "raft-state.bin" : "initialized"));
		}
		Assertions.assertThrows(IOException.class, () -> FileRaftStore.open(temporary, "group"));
		if (missing.equals("state")) {
			Assertions.assertFalse(Files.exists(temporary.resolve("raft-state.bin")));
		}
	}

	@Test
	void testInterruptedStagingWriteDoesNotReplaceAcknowledgedState() throws Exception {
		RaftIdentity identity;
		try (var store = FileRaftStore.open(temporary, "group")) {
			identity = store.identity();
			store.saveHardState(new RaftHardState(7, "node-a"));
		}
		Files.write(temporary.resolve("raft-state.pending"), new byte[] { 1, 2, 3 });
		try (var recovered = FileRaftStore.open(temporary, "group")) {
			Assertions.assertEquals(identity, recovered.identity());
			Assertions.assertEquals(new RaftHardState(7, "node-a"), recovered.hardState());
			recovered.saveHardState(new RaftHardState(8, "node-b"));
		}
		try (var recovered = FileRaftStore.open(temporary, "group")) {
			Assertions.assertEquals(new RaftHardState(8, "node-b"), recovered.hardState());
		}
	}

	@Test
	void testWriteFailureDisablesStoreUntilRecovery() throws Exception {
		try (var store = FileRaftStore.open(temporary, "group")) {
			store.saveHardState(new RaftHardState(4, "node-a"));
			Files.createDirectory(temporary.resolve("raft-state.pending"));
			Assertions.assertThrows(IOException.class, () -> store.saveHardState(new RaftHardState(5, "node-b")));
			Assertions.assertThrows(IOException.class, store::hardState);
			Assertions.assertThrows(IOException.class, store::identity);
			Assertions.assertThrows(IOException.class, () -> store.saveHardState(new RaftHardState(4, "node-a")));
		}
		Files.delete(temporary.resolve("raft-state.pending"));
		try (var store = FileRaftStore.open(temporary, "group")) {
			Assertions.assertEquals(new RaftHardState(4, "node-a"), store.hardState());
			store.saveHardState(new RaftHardState(5, "node-b"));
		}
	}

	@Test
	void testInterruptedInitializationFailsClosedAndReleasesLock() throws Exception {
		Files.createDirectory(temporary.resolve("raft-state.pending"));
		Assertions.assertThrows(IOException.class, () -> FileRaftStore.open(temporary, "group"));
		Assertions.assertTrue(Files.exists(temporary.resolve("initialized")));
		Assertions.assertThrows(IOException.class, () -> FileRaftStore.open(temporary, "group"));
		try (var channel = FileChannel.open(temporary.resolve("store.lock"), StandardOpenOption.WRITE);
			var lock = channel.tryLock()) {
			Assertions.assertNotNull(lock);
		}
	}

	@Test
	void testInvalidConfigurationDoesNotInitializeDirectory() {
		Path unused = temporary.resolve("unused");
		Assertions.assertAll(
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> FileRaftStore.open(unused, " ")),
			() -> Assertions.assertThrows(IllegalArgumentException.class,
				() -> FileRaftStore.open(unused, "group", "invalid#id")));
		Assertions.assertFalse(Files.exists(unused));
	}

	@Test
	void testDirectoryAliasCannotBypassOwnership() throws Exception {
		Path alias = temporary.resolve("alias");
		try (var store = FileRaftStore.open(temporary, "group")) {
			Files.createSymbolicLink(alias, temporary);
			Assertions.assertThrows(IOException.class, () -> FileRaftStore.open(alias, "group"));
			Assertions.assertEquals(new RaftHardState(0, null), store.hardState());
		}
	}

	@Test
	void testRejectedSecondOpenDoesNotReleaseNativeLock() throws Exception {
		try (var store = FileRaftStore.open(temporary, "group")) {
			Assertions.assertThrows(IOException.class, () -> FileRaftStore.open(temporary, "group"));
			String javaCommand = Path.of(System.getProperty("java.home"), "bin", "java").toString();
			String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
			Process probe = new ProcessBuilder(javaCommand, "-cp", classpath, LockProbe.class.getName(),
				temporary.resolve("store.lock").toString()).redirectErrorStream(true).start();
			try {
				Assertions.assertTrue(probe.waitFor(10, TimeUnit.SECONDS), "native lock probe timed out");
				String output = new String(probe.getInputStream().readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8).trim();
				Assertions.assertEquals(0, probe.exitValue(), output);
				Assertions.assertEquals("locked", output,
					"rejecting a second open must not release the first store's cross-process lock");
				Assertions.assertEquals(new RaftHardState(0, null), store.hardState());
			} finally {
				probe.destroyForcibly();
			}
		}
	}

	public static class LockProbe {
		public static void main(String[] args) throws Exception {
			try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
				var lock = channel.tryLock()) {
				System.out.println(lock == null ? "locked" : "unlocked");
			}
		}
	}

	@Test
	void testHardStateRejectsInvalidTermsAndVotes() {
		Assertions.assertAll(
			() -> Assertions.assertThrows(IllegalArgumentException.class, () -> new RaftHardState(-1, null)),
			() -> Assertions.assertThrows(IllegalArgumentException.class, () -> new RaftHardState(0, "node-a")),
			() -> Assertions.assertThrows(IllegalArgumentException.class, () -> new RaftHardState(1, "")));
	}
}
