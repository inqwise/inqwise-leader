package com.inqwise.leader;

/**
 * Durable bootstrap state; authority record creation alone does not enable participation.
 * @param genesis immutable genesis, or null before assignment
 * @param installed whether local cluster admission is durably installed
 * @param learnerAdmission whether this identity was admitted outside the genesis voter set (not its current role)
 */
public record RaftBootstrapState(RaftGenesis genesis, boolean installed, boolean learnerAdmission) {
	/** Empty, unassigned state. */
	public static final RaftBootstrapState UNASSIGNED = new RaftBootstrapState(null, false);

	/**
	 * Creates initial-voter or unassigned bootstrap state.
	 * @param genesis initial record or null
	 * @param installed installation flag
	 */
	public RaftBootstrapState(RaftGenesis genesis, boolean installed) { this(genesis, installed, false); }

	/**
	 * Rejects installation without a record.
	 * @param genesis genesis or null
	 * @param installed installation flag
	 * @param learnerAdmission admission outside the initial voter set
	 */
	public RaftBootstrapState {
		if (learnerAdmission && !installed) throw new IllegalArgumentException("Learner admission requires installation");
		if (installed && genesis == null) throw new IllegalArgumentException("Installed state requires genesis");
	}
}
