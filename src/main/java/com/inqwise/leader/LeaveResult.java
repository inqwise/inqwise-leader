package com.inqwise.leader;

import java.util.Objects;

/**
 * Authenticated confirmation of committed stable membership excluding a departing identity.
 * @param identity responding leader identity
 * @param clusterId existing cluster binding
 * @param term responding term
 * @param nodeId removed identity
 * @param configuration committed stable configuration
 * @param configurationIndex committed configuration position
 * @param commitIndex durable committed log position
 */
public record LeaveResult(RaftIdentity identity, String clusterId, long term, String nodeId,
	RaftConfiguration configuration, long configurationIndex, long commitIndex) {
	/**
	 * Validates removal evidence. This receipt does not prevent later explicit re-admission.
	 * @param identity responder
	 * @param clusterId existing binding
	 * @param term responder term
	 * @param nodeId departing identity
	 * @param configuration committed membership
	 * @param configurationIndex membership position
	 * @param commitIndex durable log commitment
	 */
	public LeaveResult {
		Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(clusterId, "clusterId");
		Objects.requireNonNull(configuration, "configuration"); RaftIdentity.validateNodeId(nodeId);
		if (clusterId.isBlank() || clusterId.length() > 1024 || term <= 0 || configurationIndex < 0
			|| commitIndex < configurationIndex || configuration.joint() || configuration.members().contains(nodeId)) {
			throw new IllegalArgumentException("Committed stable removal evidence is required");
		}
	}
}
