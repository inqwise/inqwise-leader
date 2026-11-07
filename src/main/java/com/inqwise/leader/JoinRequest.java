package com.inqwise.leader;

import java.util.Objects;

/**
 * Learner admission request bound to a freshly observed leader term and a verified callback endpoint.
 * @param identity joining persisted identity
 * @param clusterId joining node's existing binding, or null
 * @param endpoint concrete callback endpoint
 * @param leaderTerm expected leader term
 */
public record JoinRequest(RaftIdentity identity, String clusterId, NodeEndpoint endpoint, long leaderTerm) {
	/**
	 * Validates request bounds.
	 * @param identity joining identity
	 * @param clusterId existing binding
	 * @param endpoint callback endpoint
	 * @param leaderTerm expected leader term
	 */
	public JoinRequest {
		Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(endpoint, "endpoint");
		if (leaderTerm <= 0 || (clusterId != null && (clusterId.isBlank() || clusterId.length() > 1024))
			|| (endpoint.expectedNodeId() != null && !identity.nodeId().equals(endpoint.expectedNodeId()))) {
			throw new IllegalArgumentException("Invalid join request");
		}
	}
}
