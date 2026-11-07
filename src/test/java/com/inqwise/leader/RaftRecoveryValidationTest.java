package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;
import static com.inqwise.leader.VertxRaftStoreTest.await;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.vertx.core.Vertx;

class RaftRecoveryValidationTest {
	@TempDir Path directory;
	final Vertx vertx = Vertx.vertx();

	@AfterEach void shutdown() throws Exception { await(vertx.close()); }

	@FunctionalInterface interface Payload { void write(DataOutputStream out) throws IOException; }

	// Construct external-format fixtures with valid checksums, so structural validation is exercised.
	static byte[] state(Payload payload) throws Exception {
		var bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(bytes)) {
			out.writeInt(0x494C5253); out.writeInt(5);
			out.writeUTF("node-a"); out.writeUTF("group");
			out.writeLong(1); out.writeBoolean(false); out.writeLong(0);
			payload.write(out); out.flush();
			out.write(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
		}
		return bytes.toByteArray();
	}

	static byte[] entry(String kind, int size, byte[] data) throws Exception {
		return state(out -> {
			out.writeInt(1); out.writeLong(1); out.writeLong(1); out.writeUTF(kind);
			out.writeInt(size); out.write(data);
			out.writeBoolean(false); out.writeBoolean(false);
		});
	}

	static byte[] snapshot(int size, byte[] data) throws Exception {
		return state(out -> {
			out.writeInt(0); out.writeBoolean(false); out.writeBoolean(true);
			out.writeLong(1); out.writeLong(1);
			out.writeUTF(RaftConfiguration.stable(Set.of("node-a"), Set.of()).encode());
			out.writeLong(0); out.writeInt(size); out.write(data);
		});
	}

	static Stream<Arguments> corruptStates() throws Exception {
		return Stream.of(
			Arguments.of("negative log count", state(out -> out.writeInt(-1)), "Unsupported persisted log size"),
			Arguments.of("excess log count", state(out -> out.writeInt(RaftPersistentState.MAX_LOG_ENTRIES + 1)),
				"Unsupported persisted log size"),
			Arguments.of("unknown entry kind", entry("UNKNOWN", 0, new byte[0]), "Invalid persisted Raft state"),
			Arguments.of("negative entry size", entry("DATA", -1, new byte[0]), "Unsupported entry payload size"),
			Arguments.of("excess entry size", entry("DATA", RaftLogEntry.MAX_DATA_BYTES + 1, new byte[0]),
				"Unsupported entry payload size"),
			Arguments.of("truncated entry", entry("DATA", 100, new byte[] {1}), "Truncated entry payload"),
			Arguments.of("invalid entry UTF-8", entry("DATA", 1, new byte[] {(byte) 0xff}), null),
			Arguments.of("trailing bytes", state(out -> {
				out.writeInt(0); out.writeBoolean(false); out.writeBoolean(false); out.writeByte(42);
			}), "Unexpected data in Raft state"),
			Arguments.of("negative snapshot size", snapshot(-1, new byte[0]), "Unsupported snapshot size"),
			Arguments.of("excess snapshot size", snapshot(RaftSnapshot.MAX_DATA_BYTES + 1, new byte[0]),
				"Unsupported snapshot size"),
			Arguments.of("truncated snapshot", snapshot(10, new byte[] {1}), "Truncated snapshot"),
			Arguments.of("invalid snapshot UTF-8", snapshot(1, new byte[] {(byte) 0xff}), null));
	}

	@ParameterizedTest(name = "{0}") @MethodSource("corruptStates")
	void corruptStateFailsClosedWithoutReplacingEvidenceOrRetainingOwnership(String scenario, byte[] corrupt,
		String diagnostic) throws Exception {
		byte[] valid;
		try (var original = FileRaftStore.open(directory, "group", "node-a")) {
			original.saveHardState(new RaftHardState(1, "node-a"));
		}
		var file = directory.resolve("raft-state.bin"); valid = Files.readAllBytes(file);
		Files.write(file, corrupt);
		var store = VertxRaftStore.create(vertx, directory, "group", "node-a");
		try {
			var opening = store.open(); assertThrows(Exception.class, () -> await(opening), scenario);
			assertInstanceOf(IOException.class, opening.cause());
			if (diagnostic != null) assertEquals(diagnostic, opening.cause().getMessage());
			assertThrows(Exception.class, () -> await(store.persistentState()));
		} finally { await(store.close()); }
		assertArrayEquals(corrupt, Files.readAllBytes(file), "Failed recovery must preserve the damaged file");
		assertThrows(IOException.class, () -> FileRaftStore.open(directory, "group", "node-a"));
		Files.write(file, valid);
		var restored = VertxRaftStore.create(vertx, directory, "group", "node-a");
		try {
			await(restored.open());
			assertEquals(new RaftHardState(1, "node-a"), await(restored.persistentState()).hardState());
			await(restored.appendEntries(List.of(new RaftLogEntry(1, 1, RaftLogEntry.Kind.DATA, "after recovery"))));
		} finally { await(restored.close()); }
		try (var recovered = FileRaftStore.open(directory, "group", "node-a")) {
			assertEquals("after recovery", recovered.persistentState().entryAt(1).data());
		}
	}
}
