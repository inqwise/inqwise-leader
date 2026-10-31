package com.inqwise.leader;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable cluster genesis; its voter set is the initial configuration, not a membership-change API.
 * @param clusterId permanent cluster identity
 * @param groupName consensus group
 * @param authorityId designated bootstrap authority; null only for explicit manual configuration
 * @param voters unique initial voters
 * @param targetVoterCount initial desired voter count
 * @param digest canonical SHA-256 digest of every preceding field
 */
public record RaftGenesis(String clusterId, String groupName, String authorityId,
	Set<String> voters, int targetVoterCount, String digest) {
	/** Default initial voting-group size. */
	public static final int DEFAULT_VOTER_COUNT = 3;

	/**
	 * Validates and copies a genesis record, including its content digest.
	 * @param clusterId permanent cluster identity
	 * @param groupName consensus group
	 * @param authorityId authority identity, or null for manual configuration
	 * @param voters initial voters
	 * @param targetVoterCount desired voter count
	 * @param digest canonical content digest
	 */
	public RaftGenesis {
		Objects.requireNonNull(clusterId, "clusterId");
		if (clusterId.isBlank() || clusterId.length() > 1024) throw new IllegalArgumentException("Invalid cluster ID");
		new RaftIdentity(authorityId == null ? "manual" : authorityId, groupName);
		var sorted = new TreeSet<>(Objects.requireNonNull(voters, "voters"));
		sorted.forEach(RaftIdentity::validateNodeId);
		if (targetVoterCount < 1 || targetVoterCount > 63 || sorted.size() != targetVoterCount
			|| (authorityId != null && targetVoterCount % 2 == 0)) {
			throw new IllegalArgumentException("Genesis requires its full target set; automatic targets must be odd, 1..63");
		}
		voters = Collections.unmodifiableSet(sorted);
		if (!canonicalDigest(clusterId, groupName, authorityId, voters, targetVoterCount).equals(digest)) {
			throw new IllegalArgumentException("Genesis digest mismatch");
		}
	}

	/**
	 * Builds a canonical immutable record from an agreed initial configuration.
	 * @param clusterId permanent cluster identity
	 * @param groupName consensus group
	 * @param authorityId designated authority, or null for explicit manual configuration
	 * @param voters initial voters
	 * @param targetVoterCount initial target count
	 * @return record with validated digest
	 */
	public static RaftGenesis create(String clusterId, String groupName, String authorityId,
		Set<String> voters, int targetVoterCount) {
		return new RaftGenesis(clusterId, groupName, authorityId, voters, targetVoterCount,
			canonicalDigest(clusterId, groupName, authorityId, voters, targetVoterCount));
	}

	private static String canonicalDigest(String cluster, String group, String authority, Set<String> voters, int target) {
		try {
			var bytes = new ByteArrayOutputStream();
			try (var out = new DataOutputStream(bytes)) {
				out.writeUTF("inqwise-genesis-v1");
				out.writeUTF(cluster); out.writeUTF(group);
				out.writeBoolean(authority != null);
				if (authority != null) out.writeUTF(authority);
				out.writeInt(target); out.writeInt(voters.size());
				for (String voter : new TreeSet<>(voters)) out.writeUTF(voter);
			}
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
		} catch (IOException | NoSuchAlgorithmException e) {
			throw new IllegalArgumentException("Cannot encode genesis", e);
		}
	}
}
