package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.vertx.core.buffer.Buffer;

class RaftWireCodecTest {
	static Stream<RaftElection.Message> messages() {
		return Stream.of(new RaftElection.VoteRequest(2, new RaftElection.LogPosition(1, 1)),
			new RaftElection.VoteResponse(3, 2, false),
			new RaftElection.AppendEntries(3, 7, new RaftElection.LogPosition(0, 0), List.of(
				new RaftLogEntry(1, 2, RaftLogEntry.Kind.NO_OP, ""),
				new RaftLogEntry(2, 3, RaftLogEntry.Kind.DATA, "😀\n\u0000")), 1),
			new RaftElection.AppendResponse(3, 7, true, 2, 3),
			new RaftElection.InstallSnapshot(3, 8, new RaftSnapshot(5000, 2,
				RaftConfiguration.stable(java.util.Set.of("node-a"), java.util.Set.of("node-b")), 4999, "😀\n\u0000")),
			new RaftElection.SnapshotResponse(3, 8, true, 5000),
			new RaftElection.AppendEntries(3, 8, new RaftElection.LogPosition(0, 0), List.of(
				new RaftLogEntry(1, 3, RaftLogEntry.Kind.CONFIGURATION,
					RaftConfiguration.stable(java.util.Set.of("node-a"), java.util.Set.of("node-b")).encode())), 0));
	}

	@ParameterizedTest @MethodSource("messages")
	void roundTripAndRejectTruncatedOrTrailingMessages(RaftElection.Message message) throws Exception {
		var envelope = new RaftElection.Envelope("group / 雨", "cluster", "node-a", "node-b", message);
		Buffer encoded = RaftWireCodec.encode(envelope);
		assertEquals(envelope, RaftWireCodec.decode(encoded));
		for (int i = 0; i < encoded.length(); i++) {
			Buffer truncated = encoded.getBuffer(0, i);
			assertThrows(IOException.class, () -> RaftWireCodec.decode(truncated));
		}
		assertThrows(IOException.class, () -> RaftWireCodec.decode(encoded.copy().appendByte((byte) 0)));
	}

	@Test void maximumAppendBatchFitsBoundedFormat() throws Exception {
		var entries = IntStream.rangeClosed(1, 32).mapToObj(index ->
			new RaftLogEntry(index, 1, RaftLogEntry.Kind.DATA, "x".repeat(16384))).toList();
		var envelope = new RaftElection.Envelope("group", "cluster", "node-a", "node-b",
			new RaftElection.AppendEntries(1, 1, new RaftElection.LogPosition(0, 0), entries, 0));
		Buffer body = RaftWireCodec.encode(envelope);
		assertTrue(body.length() < RaftWireCodec.MAX_BYTES);
		assertEquals(envelope, RaftWireCodec.decode(body));
	}

	@Test void maximumSnapshotFitsWireLimit() throws Exception {
		var snapshot = new RaftSnapshot(5000, 3, RaftConfiguration.stable(java.util.Set.of("node-a"),
			java.util.Set.of("node-b")), 4999, "x".repeat(RaftSnapshot.MAX_DATA_BYTES));
		var envelope = new RaftElection.Envelope("group", "cluster", "node-a", "node-b",
			new RaftElection.InstallSnapshot(3, 9, snapshot));
		var body = RaftWireCodec.encode(envelope);
		assertTrue(body.length() < RaftWireCodec.MAX_BYTES); assertEquals(envelope, RaftWireCodec.decode(body));
	}

	@Test void rejectUnsupportedVersionInvalidUtf8AndOversizedLengths() throws Exception {
		Buffer body = RaftWireCodec.encode(new RaftElection.Envelope("group", "cluster", "node-a", "node-b",
			new RaftElection.VoteRequest(1, new RaftElection.LogPosition(0, 0))));
		assertThrows(IOException.class, () -> RaftWireCodec.decode(body.copy().setInt(4, 99)));
		assertThrows(IOException.class, () -> RaftWireCodec.decode(body.copy().setInt(4, 1)));
		assertThrows(IOException.class, () -> RaftWireCodec.decode(body.copy().setInt(4, 2)));
		assertThrows(IOException.class, () -> RaftWireCodec.decode(body.copy().setInt(8, Integer.MAX_VALUE)));
		assertThrows(IOException.class, () -> RaftWireCodec.decode(body.copy().setByte(12, (byte) 0xff)));
		assertThrows(IOException.class, () -> RaftWireCodec.decode(Buffer.buffer(new byte[RaftWireCodec.MAX_BYTES + 1])));
	}

	@Test void identityIsVersionedAndValidated() throws Exception {
		var identity = new RaftIdentity("node-a", "group");
		var decoded = RaftWireCodec.identity(RaftWireCodec.identity(identity, "cluster"));
		assertEquals(identity.nodeId(), decoded.nodeId());
		assertEquals(identity.groupName(), decoded.groupName());
		assertNull(RaftWireCodec.identity(RaftWireCodec.identity(identity, null)).clusterId());
	}
	@Test void bootstrapRecordRoundTripRejectsDigestDamageAndTruncation() throws Exception {
		var genesis = RaftGenesis.create("cluster", "group", "node-a", java.util.Set.of("node-a", "node-b", "node-c"), 3);
		Buffer encoded = RaftWireCodec.genesis(genesis);
		assertEquals(genesis, RaftWireCodec.genesis(encoded));
		for (int i = 0; i < encoded.length(); i++) {
			Buffer truncated = encoded.getBuffer(0, i);
			assertThrows(IOException.class, () -> RaftWireCodec.genesis(truncated));
		}
		assertThrows(IOException.class, () -> RaftWireCodec.genesis(encoded.copy().appendByte((byte) 0)));
		assertThrows(IOException.class, () -> RaftWireCodec.genesis(encoded.copy().setByte(encoded.length() - 1, (byte) '?')));
	}

	@Test void membershipStatusAndTermBoundJoinAreStrictAndBounded() throws Exception {
		var genesis = RaftGenesis.create("cluster", "group", "node-a", java.util.Set.of("node-a"), 1);
		var status = new MemberStatus(new RaftIdentity("node-a", "group"), genesis, 3, "node-a", true,
			RaftConfiguration.stable(java.util.Set.of("node-a"), java.util.Set.of("node-d")));
		var join = new JoinRequest(new RaftIdentity("node-d", "group"), null,
			new NodeEndpoint("127.0.0.1", 8081, "node-d"), 3);
		Buffer encodedStatus = RaftWireCodec.status(status); Buffer encodedJoin = RaftWireCodec.join(join);
		assertEquals(status, RaftWireCodec.status(encodedStatus)); assertEquals(join, RaftWireCodec.join(encodedJoin));
		for (int i = 0; i < encodedStatus.length(); i++) {
			Buffer truncated = encodedStatus.getBuffer(0, i); assertThrows(IOException.class, () -> RaftWireCodec.status(truncated));
		}
		for (int i = 0; i < encodedJoin.length(); i++) {
			Buffer truncated = encodedJoin.getBuffer(0, i); assertThrows(IOException.class, () -> RaftWireCodec.join(truncated));
		}
		assertThrows(IOException.class, () -> RaftWireCodec.join(encodedJoin.copy().appendByte((byte) 0)));
		assertThrows(IOException.class, () -> RaftWireCodec.status(encodedStatus.copy().appendByte((byte) 0)));
		assertThrows(IllegalArgumentException.class, () -> new MemberStatus(new RaftIdentity("node-d", "group"), genesis,
			3, "node-a", true, status.configuration()));
		assertThrows(IllegalArgumentException.class, () -> new JoinRequest(join.identity(), null, join.endpoint(), 0));
	}

	@Test void leaveRequestsAndCommittedReceiptsAreStrictAndBounded() throws Exception {
		var identity = new RaftIdentity("node-a", "group");
		var request = new LeaveRequest(new RaftIdentity("node-b", "group"), "cluster", 3);
		var result = new LeaveResult(identity, "cluster", 3, "node-b",
			RaftConfiguration.stable(java.util.Set.of("node-a"), java.util.Set.of()), 10, 11);
		var encoded = RaftWireCodec.leave(request); var receipt = RaftWireCodec.left(result);
		assertEquals(request, RaftWireCodec.leave(encoded)); assertEquals(result, RaftWireCodec.left(receipt));
		for (int i = 0; i < encoded.length(); i++) {
			var truncated = encoded.getBuffer(0, i); assertThrows(IOException.class, () -> RaftWireCodec.leave(truncated));
		}
		for (int i = 0; i < receipt.length(); i++) {
			var truncated = receipt.getBuffer(0, i); assertThrows(IOException.class, () -> RaftWireCodec.left(truncated));
		}
		assertThrows(IOException.class, () -> RaftWireCodec.leave(encoded.copy().appendByte((byte) 0)));
		assertThrows(IOException.class, () -> RaftWireCodec.left(receipt.copy().appendByte((byte) 0)));
		assertThrows(IllegalArgumentException.class, () -> new LeaveResult(identity, "cluster", 3, "node-a",
			result.configuration(), 10, 11));
		assertThrows(IllegalArgumentException.class, () -> new LeaveResult(identity, "cluster", 3, "node-b",
			result.configuration(), 12, 11));
		assertTrue(RaftWireCodec.identity(RaftWireCodec.identity(identity, "cluster", true)).departing());
		assertThrows(IOException.class, () -> RaftWireCodec.identity(RaftWireCodec.identity(identity, "cluster").setInt(4, 3)));
	}

}
