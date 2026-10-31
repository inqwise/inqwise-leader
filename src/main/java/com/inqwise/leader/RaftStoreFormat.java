package com.inqwise.leader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;

/** Shared, I/O-free format for blocking recovery tools and the Vert.x runtime store. */
final class RaftStoreFormat {
	private static final int MAGIC = 0x494C5253;
	private static final int VERSION = 5;
	private static final int DIGEST_BYTES = 32;
	static final int MAX_STATE_BYTES = 16 * 1024 * 1024;
	static final byte[] INITIALIZED = "inqwise-raft-store-v1\n".getBytes(StandardCharsets.US_ASCII);

	private RaftStoreFormat() {}

	static void validateTransition(RaftPersistentState previous, RaftPersistentState next) {
		RaftHardState hardState = previous.hardState();
		if (next.hardState().currentTerm() < hardState.currentTerm()) {
			throw new IllegalArgumentException("Raft term cannot decrease");
		}
		if (next.hardState().currentTerm() == hardState.currentTerm() && hardState.votedFor() != null
			&& !hardState.votedFor().equals(next.hardState().votedFor())) {
			throw new IllegalArgumentException("A vote cannot be cleared or changed within the same term");
		}
		if (next.commitIndex() < previous.commitIndex() || next.baseIndex() < previous.baseIndex()) {
			throw new IllegalArgumentException("Snapshot and commitment cannot move backwards");
		}
		if (next.baseIndex() == previous.baseIndex() && !java.util.Objects.equals(next.snapshot(), previous.snapshot())) {
			throw new IllegalArgumentException("An existing snapshot boundary cannot be replaced");
		}
		if (next.baseIndex() > previous.baseIndex() && next.baseIndex() <= previous.commitIndex()
			&& next.snapshot().lastIncludedTerm() != previous.termAt(next.baseIndex())) {
			throw new IllegalArgumentException("Compaction changed a committed boundary term");
		}
		for (long index = Math.max(previous.baseIndex(), next.baseIndex()) + 1; index <= previous.commitIndex(); index++) {
			if (index > next.lastIndex() || !previous.entryAt(index).equals(next.entryAt(index))) {
				throw new IllegalArgumentException("Committed retained entries cannot be overwritten");
			}
		}
	}

	static byte[] encode(StoredState state) throws IOException {
		validateBinding(state.identity(), state.bootstrap());
		validateProtocolBinding(state.state(), state.bootstrap());
		var bytes = new ByteArrayOutputStream();
		try (var output = new DataOutputStream(bytes)) {
			output.writeInt(MAGIC);
			output.writeInt(VERSION);
			output.writeUTF(state.identity().nodeId());
			output.writeUTF(state.identity().groupName());
			output.writeLong(state.state().hardState().currentTerm());
			output.writeBoolean(state.state().hardState().votedFor() != null);
			if (state.state().hardState().votedFor() != null) {
				output.writeUTF(state.state().hardState().votedFor());
			}
			output.writeLong(state.state().commitIndex());
			output.writeInt(state.state().log().size());
			for (RaftLogEntry entry : state.state().log()) {
				output.writeLong(entry.index());
				output.writeLong(entry.term());
				output.writeUTF(entry.kind().name());
				byte[] data = entry.data().getBytes(StandardCharsets.UTF_8);
				output.writeInt(data.length);
				output.write(data);
			}
			writeBootstrap(output, state.bootstrap());
			output.writeBoolean(state.state().snapshot() != null);
			if (state.state().snapshot() != null) writeSnapshot(output, state.state().snapshot());
			output.flush();
			byte[] payload = bytes.toByteArray();
			output.write(digest(payload));
		}
		return bytes.toByteArray();
	}

	static StoredState decode(byte[] bytes) throws IOException {
		if (bytes.length <= DIGEST_BYTES) {
			throw new IOException("Truncated Raft state");
		}
		byte[] payload = Arrays.copyOf(bytes, bytes.length - DIGEST_BYTES);
		byte[] checksum = Arrays.copyOfRange(bytes, payload.length, bytes.length);
		if (!MessageDigest.isEqual(digest(payload), checksum)) {
			throw new IOException("Raft state checksum mismatch");
		}
		try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
			int magic = input.readInt();
			int version = input.readInt();
			if (magic != MAGIC || (version < 1 || version > VERSION)) {
				throw new IOException("Unsupported Raft state format");
			}
			RaftIdentity identity = new RaftIdentity(input.readUTF(), input.readUTF());
			long term = input.readLong();
			RaftHardState hardState = new RaftHardState(term, input.readBoolean() ? input.readUTF() : null);
			long commitIndex = version == 1 ? 0 : input.readLong();
			int count = version == 1 ? 0 : input.readInt();
			if (count < 0 || count > RaftPersistentState.MAX_LOG_ENTRIES) {
				throw new IOException("Unsupported persisted log size");
			}
			var log = new ArrayList<RaftLogEntry>(count);
			for (int i = 0; i < count; i++) {
				long index = input.readLong();
				long entryTerm = input.readLong();
				RaftLogEntry.Kind kind = RaftLogEntry.Kind.valueOf(input.readUTF());
				int length = input.readInt();
				if (length < 0 || length > RaftLogEntry.MAX_DATA_BYTES) {
					throw new IOException("Unsupported entry payload size");
				}
				byte[] data = input.readNBytes(length);
				if (data.length != length) {
					throw new IOException("Truncated entry payload");
				}
				String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(data)).toString();
				log.add(new RaftLogEntry(index, entryTerm, kind, text));
			}
			RaftBootstrapState bootstrap = version >= 3 ? readBootstrap(input, version) : RaftBootstrapState.UNASSIGNED;
			RaftSnapshot snapshot = version >= 5 && input.readBoolean() ? readSnapshot(input) : null;
			if (input.available() != 0) {
				throw new IOException("Unexpected data in Raft state");
			}
			var result = new StoredState(identity, new RaftPersistentState(hardState, log, commitIndex, snapshot), bootstrap);
			validateBinding(identity, bootstrap);
			validateProtocolBinding(result.state(), bootstrap);
			return result;
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid persisted Raft state", e);
		}
	}

	static void validateSnapshotTransition(RaftPersistentState previous, RaftPersistentState next, RaftBootstrapState binding) {
		if (next.baseIndex() > previous.baseIndex() && next.baseIndex() <= previous.commitIndex() && binding.genesis() != null) {
			var prefix = previous.log().stream().filter(entry -> entry.index() <= next.baseIndex()).toList();
			var history = RaftConfiguration.history(binding.genesis().voters(), prefix, next.baseIndex(), previous.snapshot());
			if (!history.effective().equals(next.snapshot().configuration()) || history.index() != next.snapshot().configurationIndex()) {
				throw new IllegalArgumentException("Snapshot changed committed membership");
			}
		}
	}

	static void writeSnapshot(DataOutputStream out, RaftSnapshot snapshot) throws IOException {
		out.writeLong(snapshot.lastIncludedIndex()); out.writeLong(snapshot.lastIncludedTerm());
		out.writeUTF(snapshot.configuration().encode()); out.writeLong(snapshot.configurationIndex());
		byte[] data = snapshot.data().getBytes(StandardCharsets.UTF_8); out.writeInt(data.length); out.write(data);
	}

	static RaftSnapshot readSnapshot(DataInputStream in) throws IOException {
		long index = in.readLong(); long term = in.readLong();
		var configuration = RaftConfiguration.decode(in.readUTF()); long configuredAt = in.readLong(); int size = in.readInt();
		if (size < 0 || size > RaftSnapshot.MAX_DATA_BYTES) throw new IOException("Unsupported snapshot size");
		byte[] data = in.readNBytes(size); if (data.length != size) throw new IOException("Truncated snapshot");
		String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(data)).toString();
		return new RaftSnapshot(index, term, configuration, configuredAt, value);
	}

	private static byte[] digest(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the runtime", e);
		}
	}

	static record StoredState(RaftIdentity identity, RaftPersistentState state, RaftBootstrapState bootstrap) {
		StoredState(RaftIdentity identity, RaftPersistentState state) {
			this(identity, state, RaftBootstrapState.UNASSIGNED);
		}
	}

	static void writeGenesis(DataOutputStream out, RaftGenesis genesis) throws IOException {
		out.writeUTF(genesis.clusterId()); out.writeUTF(genesis.groupName());
		out.writeBoolean(genesis.authorityId() != null);
		if (genesis.authorityId() != null) out.writeUTF(genesis.authorityId());
		out.writeInt(genesis.targetVoterCount()); out.writeInt(genesis.voters().size());
		for (String voter : genesis.voters()) out.writeUTF(voter);
		out.writeUTF(genesis.digest());
	}

	static RaftGenesis readGenesis(DataInputStream in) throws IOException {
		String cluster = in.readUTF(); String group = in.readUTF();
		String authority = in.readBoolean() ? in.readUTF() : null;
		int target = in.readInt(); int count = in.readInt();
		if (count < 1 || count > 63) throw new IOException("Invalid genesis voter count");
		var voters = new java.util.TreeSet<String>();
		for (int i = 0; i < count; i++) {
			if (!voters.add(in.readUTF())) throw new IOException("Duplicate genesis voter");
		}
		return new RaftGenesis(cluster, group, authority, voters, target, in.readUTF());
	}

	private static void writeBootstrap(DataOutputStream out, RaftBootstrapState state) throws IOException {
		out.writeBoolean(state.genesis() != null);
		if (state.genesis() != null) { writeGenesis(out, state.genesis()); out.writeBoolean(state.installed()); out.writeBoolean(state.learnerAdmission()); }
	}

	private static RaftBootstrapState readBootstrap(DataInputStream in, int version) throws IOException {
		if (!in.readBoolean()) return RaftBootstrapState.UNASSIGNED;
		var genesis = readGenesis(in);
		return new RaftBootstrapState(genesis, in.readBoolean(), version >= 4 && in.readBoolean());
	}

	static void validateBinding(RaftIdentity identity, RaftBootstrapState state) {
		if (state.genesis() == null) return;
		if (!identity.groupName().equals(state.genesis().groupName())
			|| (state.installed() && !state.learnerAdmission() && !state.genesis().voters().contains(identity.nodeId()))
			|| (state.learnerAdmission() && state.genesis().voters().contains(identity.nodeId()))
			|| (!state.installed() && !identity.nodeId().equals(state.genesis().authorityId()))) {
			throw new IllegalArgumentException("Invalid local genesis binding");
		}
	}

	static void validateProtocolBinding(RaftPersistentState state, RaftBootstrapState bootstrap) {
		if (bootstrap.genesis() != null) {
			if (!bootstrap.installed()) requireEmpty(state);
			else RaftConfiguration.history(bootstrap.genesis().voters(), state.log(), state.commitIndex(), state.snapshot());
		}
	}

	static void requireEmpty(RaftPersistentState state) {
		if (state.hardState().currentTerm() != 0 || state.hardState().votedFor() != null
			|| !state.log().isEmpty() || state.commitIndex() != 0 || state.snapshot() != null) {
			throw new IllegalArgumentException("Unbound protocol history cannot be assigned to a cluster");
		}
	}

	static RaftBootstrapState install(RaftIdentity identity, RaftPersistentState state,
		RaftBootstrapState previous, RaftGenesis genesis) {
		java.util.Objects.requireNonNull(genesis, "genesis");
		if (previous.genesis() != null && !previous.genesis().equals(genesis)) {
			throw new IllegalArgumentException("Conflicting immutable genesis");
		}
		var next = new RaftBootstrapState(genesis, true);
		validateBinding(identity, next);
		if (!previous.installed()) requireEmpty(state);
		return next;
	}

	static RaftBootstrapState admitLearner(RaftIdentity identity, RaftPersistentState state,
		RaftBootstrapState previous, RaftGenesis genesis) {
		java.util.Objects.requireNonNull(genesis, "genesis");
		if (previous.genesis() != null && !previous.genesis().equals(genesis)) {
			throw new IllegalArgumentException("Conflicting immutable genesis");
		}
		var next = new RaftBootstrapState(genesis, true, true);
		validateBinding(identity, next);
		if (!previous.installed()) requireEmpty(state);
		return next;
	}

	static RaftGenesis form(RaftIdentity identity, RaftPersistentState state, RaftBootstrapState previous,
		java.util.Set<String> eligible, int target) {
		if (previous.genesis() != null) {
			if (!identity.nodeId().equals(previous.genesis().authorityId())) {
				throw new IllegalArgumentException("This node is not the recorded authority");
			}
			return previous.genesis();
		}
		requireEmpty(state);
		if (target < 1 || target > 63 || target % 2 == 0) throw new IllegalArgumentException("Invalid initial target");
		var sorted = new java.util.TreeSet<>(eligible);
		sorted.forEach(RaftIdentity::validateNodeId);
		if (sorted.size() < target) throw new IllegalStateException("Bootstrap pending: fewer eligible nodes than target");
		var selected = new java.util.TreeSet<>(sorted.stream().limit(target).toList());
		return RaftGenesis.create(java.util.UUID.randomUUID().toString(), identity.groupName(), identity.nodeId(),
			selected, target);
	}
}
