package com.inqwise.leader;

import java.util.Objects;

/**
 * Authenticated self-removal request bound to an observed leader term.
 * @param identity departing persisted identity
 * @param clusterId existing cluster binding
 * @param leaderTerm expected leader term
 */
public record LeaveRequest(RaftIdentity identity, String clusterId, long leaderTerm) {
	/**
	 * Validates required binding and term.
	 * @param identity departing node
	 * @param clusterId existing cluster
	 * @param leaderTerm observed leader term
	 */
	public LeaveRequest {
		Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(clusterId, "clusterId");
		if (clusterId.isBlank() || clusterId.length() > 1024 || leaderTerm <= 0) {
			throw new IllegalArgumentException("A bound cluster and positive leader term are required");
		}
	}
}
