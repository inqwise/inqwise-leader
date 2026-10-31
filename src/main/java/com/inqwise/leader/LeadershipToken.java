package com.inqwise.leader;

import java.util.Objects;

/**
 * Fencing epoch for one persisted Raft group and cluster. Terms are ordered only within that binding.
 * A destination must authenticate callers and atomically enforce its highest accepted term with every side effect.
 * This value is neither a lease nor a cryptographic proof of current leadership.
 * @param groupName consensus group authorized for the destination resource
 * @param clusterId immutable persisted cluster identity
 * @param term positive term observed while the local node was ready to lead
 */
public record LeadershipToken(String groupName, String clusterId, long term) {
	/**
	 * Validates a token value; construction alone does not establish leadership or authorize a destination operation.
	 * @param groupName nonblank consensus group, at most 1024 characters
	 * @param clusterId nonblank persisted cluster identity, at most 1024 characters
	 * @param term positive Raft term
	 */
	public LeadershipToken {
		Objects.requireNonNull(groupName, "groupName"); Objects.requireNonNull(clusterId, "clusterId");
		if (groupName.isBlank() || groupName.length() > 1024 || clusterId.isBlank() || clusterId.length() > 1024 || term < 1) {
			throw new IllegalArgumentException("A fencing token requires bounded group/cluster identities and a positive term");
		}
	}
}
