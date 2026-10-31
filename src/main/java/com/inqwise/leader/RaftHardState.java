package com.inqwise.leader;

/**
 * Term and vote persisted together before dependent protocol effects are released.
 *
 * @param currentTerm nonnegative current term
 * @param votedFor candidate ID, or {@code null} when no vote has been cast in this term
 */
public record RaftHardState(long currentTerm, String votedFor) {
	/**
	 * Creates validated state; transition validation is performed by the store.
	 * @param currentTerm nonnegative current term
	 * @param votedFor candidate ID, or {@code null}
	 */
	public RaftHardState {
		if (currentTerm < 0 || (currentTerm == 0 && votedFor != null)) {
			throw new IllegalArgumentException("term must be nonnegative; term zero cannot contain a vote");
		}
		if (votedFor != null) {
			RaftIdentity.validateNodeId(votedFor);
		}
	}
}
