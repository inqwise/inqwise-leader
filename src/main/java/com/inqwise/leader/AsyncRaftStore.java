package com.inqwise.leader;

import java.util.List;
import java.util.Set;

import io.vertx.core.Context;
import io.vertx.core.Future;

/**
 * Context-owned persistence API. Writes complete only after durability is confirmed.
 * Operations are safe to submit from other threads and are serialized on the owning context.
 */
public interface AsyncRaftStore {
	/**
	 * Identifies the owner for protocol and storage state.
	 * @return the event-loop context owning store state
	 */
	Context context();

	/**
	 * Acquires exclusive ownership and creates or recovers durable state.
	 * @return completion of exclusive acquisition and recovery
	 */
	Future<Void> open();

	/**
	 * Reads identity after opening.
	 * @return recovered immutable identity
	 */
	Future<RaftIdentity> identity();

	/**
	 * Reads the last confirmed durable snapshot.
	 * @return one consistent durable snapshot
	 */
	Future<RaftPersistentState> persistentState();

	/**
	 * Reads immutable cluster assignment and installation status.
	 * @return durable bootstrap state
	 */
	Future<RaftBootstrapState> bootstrapState();

	/**
	 * Atomically forms the authority's immutable record, or returns its existing record.
	 * @param eligible verified eligible identities
	 * @param target initial odd voter count
	 * @return original durable genesis; never replaces an existing initial set
	 */
	Future<RaftGenesis> createGenesis(Set<String> eligible, int target);

	/**
	 * Installs an exact initial configuration on an empty selected voter; identical retries are harmless.
	 * @param genesis authenticated immutable record
	 * @return confirmed durable installation
	 */
	Future<Void> installGenesis(RaftGenesis genesis);

	/**
	 * Records trusted admission outside the genesis voters. It does not grant voting rights.
	 * The caller must authorize admission and supply the established cluster's original genesis.
	 * @param genesis established immutable record
	 * @return confirmed durable admission
	 */
	Future<Void> installLearnerGenesis(RaftGenesis genesis);

	/**
	 * Replaces the complete state while preserving committed history.
	 * @param state full snapshot to persist
	 * @return confirmed durability
	 */
	Future<Void> savePersistentState(RaftPersistentState state);

	/**
	 * Advances the election term and vote.
	 * @param state new term/vote
	 * @return confirmed durability preserving log/commit state
	 */
	Future<Void> saveHardState(RaftHardState state);

	/**
	 * Appends a contiguous log suffix.
	 * @param entries contiguous suffix
	 * @return confirmed durability
	 */
	Future<Void> appendEntries(List<RaftLogEntry> entries);

	/**
	 * Removes an uncommitted log suffix.
	 * @param fromIndex first uncommitted entry to remove
	 * @return confirmed durability
	 */
	Future<Void> truncateSuffix(long fromIndex);

	/**
	 * Rejects new operations and closes after accepted operations finish.
	 * @return completion after accepted operations finish and ownership is released
	 */
	Future<Void> close();
}
