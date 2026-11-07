package com.inqwise.leader;

import java.util.Objects;

/**
 * Authenticated peer status; a ready leader is the only peer permitted to authorize learner admission.
 * @param identity responding persisted identity
 * @param genesis original immutable cluster record
 * @param term current term
 * @param leaderId known leader, or null
 * @param ready whether the responder is a ready local leader
 * @param configuration responder's latest effective membership
 */
public record MemberStatus(RaftIdentity identity, RaftGenesis genesis, long term, String leaderId,
	boolean ready, RaftConfiguration configuration) {
	/**
	 * Validates status consistency.
	 * @param identity responding node
	 * @param genesis immutable binding
	 * @param term current term
	 * @param leaderId known leader
	 * @param ready local readiness
	 * @param configuration effective configuration
	 */
	public MemberStatus {
		Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(genesis, "genesis");
		Objects.requireNonNull(configuration, "configuration");
		if (leaderId != null) RaftIdentity.validateNodeId(leaderId);
		if (term < 0 || !identity.groupName().equals(genesis.groupName())
			|| (ready && (term == 0 || !identity.nodeId().equals(leaderId)
				|| !configuration.voters().contains(identity.nodeId())))) {
			throw new IllegalArgumentException("Invalid member status");
		}
	}
}
