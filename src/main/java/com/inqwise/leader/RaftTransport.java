package com.inqwise.leader;

import io.vertx.core.Future;

/** Transport boundary; implementations must authenticate and authorize peer identities for this cluster before receive. */
@FunctionalInterface
public interface RaftTransport {
	/**
	 * Verifies transport policy agrees with the local durable configuration before participation.
	 * @param identity recovered local identity
	 * @param genesis local installed or explicitly proposed initial binding
	 * @return validation result; this operation must not alter cluster assignment
	 */
	default Future<Void> validateBinding(RaftIdentity identity, RaftGenesis genesis) { return Future.succeededFuture(); }

	/**
	 * Validates a voter or explicitly admitted learner before participation.
	 * @param identity persistent local identity
	 * @param genesis immutable initial binding
	 * @param learnerAdmission whether initial admission was outside genesis voters
	 * @return transport policy validation
	 */
	default Future<Void> validateBinding(RaftIdentity identity, RaftGenesis genesis, boolean learnerAdmission) {
		return validateBinding(identity, genesis);
	}

	/**
	 * Submits a protocol message without blocking the event loop.
	 * Completion means transport acceptance, not durable acknowledgement by the peer.
	 * @param envelope addressed protocol message
	 * @return transport submission result
	 */
	Future<Void> send(RaftElection.Envelope envelope);
}
