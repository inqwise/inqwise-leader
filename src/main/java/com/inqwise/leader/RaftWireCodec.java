package com.inqwise.leader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import io.vertx.core.buffer.Buffer;

/** Bounded, explicitly versioned transport format; deliberately separate from the durable store format. */
final class RaftWireCodec {
	static final int VERSION = 4;
	static final int MAX_BYTES = 600 * 1024;
	static final String CONTENT_TYPE = "application/vnd.inqwise.raft";
	private static final int MAGIC = 0x49525150;

	private RaftWireCodec() {}

	static Buffer encode(RaftElection.Envelope envelope) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(bytes)) {
			header(out);
			text(out, envelope.groupName(), 4096);
			text(out, envelope.clusterId(), 4096);
			text(out, envelope.from(), 128);
			text(out, envelope.to(), 128);
			var message = envelope.message();
			out.writeByte(type(message));
			out.writeLong(message.term());
			if (message instanceof RaftElection.VoteRequest vote) {
				position(out, vote.lastLog());
			} else if (message instanceof RaftElection.VoteResponse vote) {
				out.writeLong(vote.electionTerm());
				out.writeBoolean(vote.granted());
			} else if (message instanceof RaftElection.AppendEntries append) {
				out.writeLong(append.requestId());
				position(out, append.previous());
				out.writeLong(append.leaderCommit());
				out.writeInt(append.entries().size());
				for (var entry : append.entries()) {
					out.writeLong(entry.index());
					out.writeLong(entry.term());
					out.writeByte(entry.kind().ordinal());
					text(out, entry.data(), RaftLogEntry.MAX_DATA_BYTES);
				}
			} else if (message instanceof RaftElection.AppendResponse append) {
				out.writeLong(append.requestId());
				out.writeBoolean(append.success());
				out.writeLong(append.matchIndex());
				out.writeLong(append.nextIndexHint());
			} else if (message instanceof RaftElection.InstallSnapshot install) {
				out.writeLong(install.requestId());
				RaftStoreFormat.writeSnapshot(out, install.snapshot());
			} else if (message instanceof RaftElection.SnapshotResponse installed) {
				out.writeLong(installed.requestId()); out.writeBoolean(installed.accepted());
				out.writeLong(installed.lastIncludedIndex());
			}
		}
		return bounded(bytes);
	}

	static RaftElection.Envelope decode(Buffer body) throws IOException {
		try (var in = input(body)) {
			String group = text(in, 4096);
			String cluster = text(in, 4096);
			String from = text(in, 128);
			String to = text(in, 128);
			int type = in.readUnsignedByte();
			long term = in.readLong();
			RaftElection.Message message = switch (type) {
				case 1 -> new RaftElection.VoteRequest(term, position(in));
				case 2 -> new RaftElection.VoteResponse(term, in.readLong(), bool(in));
				case 3 -> {
					long requestId = in.readLong();
					var previous = position(in);
					long commit = in.readLong();
					int count = in.readInt();
					if (count < 0 || count > RaftElection.MAX_APPEND_ENTRIES) throw new IOException("Invalid batch size");
					var entries = new ArrayList<RaftLogEntry>(count);
					for (int i = 0; i < count; i++) {
						long index = in.readLong();
						long entryTerm = in.readLong();
						int kind = in.readUnsignedByte();
						if (kind >= RaftLogEntry.Kind.values().length) throw new IOException("Unknown entry kind");
						entries.add(new RaftLogEntry(index, entryTerm, RaftLogEntry.Kind.values()[kind],
							text(in, RaftLogEntry.MAX_DATA_BYTES)));
					}
					yield new RaftElection.AppendEntries(term, requestId, previous, entries, commit);
				}
				case 4 -> new RaftElection.AppendResponse(term, in.readLong(), bool(in), in.readLong(), in.readLong());
				case 5 -> new RaftElection.InstallSnapshot(term, in.readLong(), RaftStoreFormat.readSnapshot(in));
				case 6 -> new RaftElection.SnapshotResponse(term, in.readLong(), bool(in), in.readLong());
				default -> throw new IOException("Unknown protocol message");
			};
			if (in.available() != 0) throw new IOException("Trailing protocol bytes");
			return new RaftElection.Envelope(group, cluster, from, to, message);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new IOException("Invalid protocol message", e);
		}
	}

	static Buffer identity(RaftIdentity identity, String clusterId) throws IOException { return identity(identity, clusterId, false); }

	static Buffer identity(RaftIdentity identity, String clusterId, boolean departing) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(bytes)) {
			header(out);
			text(out, identity.nodeId(), 128);
			text(out, identity.groupName(), 4096);
			text(out, clusterId == null ? "" : clusterId, 4096); out.writeBoolean(departing);
		}
		return bounded(bytes);
	}

	static PeerIdentity identity(Buffer body) throws IOException {
		try (var in = input(body)) {
			String node = text(in, 128);
			String group = text(in, 4096);
			String cluster = text(in, 4096); boolean departing = bool(in);
			new RaftIdentity(node, group);
			if ((!cluster.isEmpty() && cluster.isBlank()) || in.available() != 0) throw new IOException("Invalid identity response");
			return new PeerIdentity(node, group, cluster.isEmpty() ? null : cluster, departing);
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid identity response", e);
		}
	}

	static Buffer genesis(RaftGenesis genesis) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(bytes)) {
			header(out);
			RaftStoreFormat.writeGenesis(out, genesis);
		}
		return bounded(bytes);
	}

	static RaftGenesis genesis(Buffer bytes) throws IOException {
		try (var in = input(bytes)) {
			var genesis = RaftStoreFormat.readGenesis(in);
			if (genesis.authorityId() == null || in.available() != 0) throw new IOException("Invalid authority record");
			return genesis;
		} catch (IllegalArgumentException e) { throw new IOException("Invalid genesis", e); }
	}

	static Buffer status(MemberStatus status) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(bytes)) {
			header(out); text(out, status.identity().nodeId(), 128);
			RaftStoreFormat.writeGenesis(out, status.genesis()); out.writeLong(status.term());
			text(out, status.leaderId() == null ? "" : status.leaderId(), 128);
			out.writeBoolean(status.ready()); text(out, status.configuration().encode(), RaftLogEntry.MAX_DATA_BYTES);
		}
		return bounded(bytes);
	}

	static MemberStatus status(Buffer bytes) throws IOException {
		try (var in = input(bytes)) {
			String id = text(in, 128); var genesis = RaftStoreFormat.readGenesis(in); long term = in.readLong();
			String leader = text(in, 128); boolean ready = bool(in);
			var configuration = RaftConfiguration.decode(text(in, RaftLogEntry.MAX_DATA_BYTES));
			if (in.available() != 0) throw new IOException("Trailing member status bytes");
			return new MemberStatus(new RaftIdentity(id, genesis.groupName()), genesis, term,
				leader.isEmpty() ? null : leader, ready, configuration);
		} catch (IllegalArgumentException e) { throw new IOException("Invalid member status", e); }
	}

	static Buffer join(JoinRequest request) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(bytes)) {
			header(out); text(out, request.identity().nodeId(), 128); text(out, request.identity().groupName(), 4096);
			text(out, request.clusterId() == null ? "" : request.clusterId(), 4096);
			text(out, request.endpoint().host(), 15); out.writeInt(request.endpoint().port()); out.writeLong(request.leaderTerm());
		}
		return bounded(bytes);
	}

	static JoinRequest join(Buffer bytes) throws IOException {
		try (var in = input(bytes)) {
			var identity = new RaftIdentity(text(in, 128), text(in, 4096)); String cluster = text(in, 4096);
			var endpoint = new NodeEndpoint(text(in, 15), in.readInt(), identity.nodeId()); long term = in.readLong();
			if (in.available() != 0) throw new IOException("Trailing join bytes");
			return new JoinRequest(identity, cluster.isEmpty() ? null : cluster, endpoint, term);
		} catch (IllegalArgumentException e) { throw new IOException("Invalid join request", e); }
	}

	static Buffer leave(LeaveRequest request) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(bytes)) {
			header(out); text(out, request.identity().nodeId(), 128); text(out, request.identity().groupName(), 4096);
			text(out, request.clusterId(), 4096); out.writeLong(request.leaderTerm());
		}
		return bounded(bytes);
	}

	static LeaveRequest leave(Buffer bytes) throws IOException {
		try (var in = input(bytes)) {
			var request = new LeaveRequest(new RaftIdentity(text(in, 128), text(in, 4096)), text(in, 4096), in.readLong());
			if (in.available() != 0) throw new IOException("Trailing leave request bytes");
			return request;
		} catch (IllegalArgumentException e) { throw new IOException("Invalid leave request", e); }
	}

	static Buffer left(LeaveResult result) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(bytes)) {
			header(out); text(out, result.identity().nodeId(), 128); text(out, result.identity().groupName(), 4096);
			text(out, result.clusterId(), 4096); out.writeLong(result.term()); text(out, result.nodeId(), 128);
			text(out, result.configuration().encode(), RaftLogEntry.MAX_DATA_BYTES);
			out.writeLong(result.configurationIndex()); out.writeLong(result.commitIndex());
		}
		return bounded(bytes);
	}

	static LeaveResult left(Buffer bytes) throws IOException {
		try (var in = input(bytes)) {
			var identity = new RaftIdentity(text(in, 128), text(in, 4096)); String cluster = text(in, 4096);
			long term = in.readLong(); String removed = text(in, 128);
			var configuration = RaftConfiguration.decode(text(in, RaftLogEntry.MAX_DATA_BYTES));
			var result = new LeaveResult(identity, cluster, term, removed, configuration, in.readLong(), in.readLong());
			if (in.available() != 0) throw new IOException("Trailing leave result bytes");
			return result;
		} catch (IllegalArgumentException e) { throw new IOException("Invalid leave result", e); }
	}

	record PeerIdentity(String nodeId, String groupName, String clusterId, boolean departing) {}

	static String suffix(RaftElection.Message message) {
		return switch (type(message)) {
			case 1 -> "/request-vote";
			case 2 -> "/vote-response";
			case 3 -> "/append-entries";
			case 4 -> "/append-response";
			case 5 -> "/install-snapshot";
			case 6 -> "/snapshot-response";
			default -> throw new IllegalArgumentException("Unknown message");
		};
	}

	private static int type(RaftElection.Message message) {
		if (message instanceof RaftElection.VoteRequest) return 1;
		if (message instanceof RaftElection.VoteResponse) return 2;
		if (message instanceof RaftElection.AppendEntries) return 3;
		if (message instanceof RaftElection.AppendResponse) return 4;
		if (message instanceof RaftElection.InstallSnapshot) return 5;
		if (message instanceof RaftElection.SnapshotResponse) return 6;
		throw new IllegalArgumentException("Unknown message");
	}

	private static void header(DataOutputStream out) throws IOException {
		out.writeInt(MAGIC);
		out.writeInt(VERSION);
	}

	private static DataInputStream input(Buffer body) throws IOException {
		if (body.length() > MAX_BYTES) throw new IOException("Protocol body too large");
		var in = new DataInputStream(new ByteArrayInputStream(body.getBytes()));
		if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported wire protocol");
		return in;
	}

	private static Buffer bounded(ByteArrayOutputStream bytes) throws IOException {
		if (bytes.size() > MAX_BYTES) throw new IOException("Protocol body too large");
		return Buffer.buffer(bytes.toByteArray());
	}

	private static void position(DataOutputStream out, RaftElection.LogPosition position) throws IOException {
		out.writeLong(position.index()); out.writeLong(position.term());
	}

	private static RaftElection.LogPosition position(DataInputStream in) throws IOException {
		return new RaftElection.LogPosition(in.readLong(), in.readLong());
	}

	private static boolean bool(DataInputStream in) throws IOException {
		int value = in.readUnsignedByte();
		if (value > 1) throw new IOException("Invalid boolean");
		return value == 1;
	}

	private static void text(DataOutputStream out, String text, int limit) throws IOException {
		var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
			.encode(java.nio.CharBuffer.wrap(text));
		if (encoded.remaining() > limit) throw new IOException("Protocol string too large");
		byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
		out.writeInt(bytes.length); out.write(bytes);
	}

	private static String text(DataInputStream in, int limit) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > limit || length > in.available()) throw new IOException("Invalid string size");
		return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(in.readNBytes(length))).toString();
	}
}
