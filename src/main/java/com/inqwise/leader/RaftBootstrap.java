package com.inqwise.leader;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import io.vertx.core.Context;
import io.vertx.core.Future;

/** Designated-authority bootstrap operations; discovery is eligibility evidence, never voting membership. */
public final class RaftBootstrap {
	private final AsyncRaftStore store;
	private final String authorityId;
	private final int target;

	/**
	 * Uses the default initial voter count of three.
	 * @param store local durable store
	 * @param authorityId explicitly designated persistent authority identity
	 */
	public RaftBootstrap(AsyncRaftStore store, String authorityId) {
		this(store, authorityId, RaftGenesis.DEFAULT_VOTER_COUNT);
	}

	/**
	 * Configures bootstrap; an existing authority record always wins over a new target setting.
	 * @param store local durable store
	 * @param authorityId designated authority identity
	 * @param target initial odd target count, 1..63
	 */
	public RaftBootstrap(AsyncRaftStore store, String authorityId, int target) {
		this.store = Objects.requireNonNull(store, "store");
		RaftIdentity.validateNodeId(Objects.requireNonNull(authorityId, "authorityId"));
		if (target < 1 || target > 63 || target % 2 == 0) throw new IllegalArgumentException("Invalid initial voter count");
		this.authorityId = authorityId; this.target = target;
	}

	/**
	 * Identifies the context owning bootstrap operations.
	 * @return owner context
	 */
	public Context context() { return store.context(); }
	/**
	 * Identifies the explicitly designated bootstrap authority.
	 * @return configured authority identity
	 */
	public String authorityId() { return authorityId; }
	AsyncRaftStore store() { return store; }

	/**
	 * Opens local storage and verifies any existing authority binding.
	 * @return durable recovery completion
	 */
	public Future<Void> open() {
		return ContextDispatch.call(context(), () -> store.open().compose(v -> state()).mapEmpty());
	}

	/**
	 * Reads the durable record without interpreting discovery disappearance as loss of membership.
	 * @return persisted bootstrap assignment
	 */
	public Future<RaftBootstrapState> state() {
		return ContextDispatch.call(context(), () -> store.bootstrapState().map(binding -> {
			if (binding.genesis() != null && !authorityId.equals(binding.genesis().authorityId())) {
				throw new IllegalStateException("Configured authority differs from immutable genesis");
			}
			return binding;
		}));
	}

	/**
	 * Selects a full target set once, including the local authority as an eligible candidate.
	 * Existing records are returned unchanged even when candidates disappear or local target settings change.
	 * @param peers authenticated identity-handshake results
	 * @return original immutable record after confirmed local durability
	 */
	public Future<RaftGenesis> form(List<DiscoveredPeer> peers) {
		var copy = List.copyOf(peers);
		return ContextDispatch.call(context(), () -> store.identity().compose(identity -> {
			if (!authorityId.equals(identity.nodeId())) return Future.failedFuture("Only the designated authority can form genesis");
			return state().compose(binding -> {
				if (binding.genesis() != null) return Future.succeededFuture(binding.genesis());
				if (copy.size() > 64) return Future.failedFuture("Too many bootstrap candidates");
				var eligible = new TreeSet<String>(); eligible.add(identity.nodeId());
				var endpoints = new HashMap<String, NodeEndpoint>();
				for (var peer : copy) {
					if (!identity.groupName().equals(peer.identity().groupName()) || peer.clusterId() != null) {
						return Future.failedFuture("Bootstrap conflict: candidate is assigned or belongs to another group");
					}
					if (peer.departing()) continue;
					var previous = endpoints.putIfAbsent(peer.identity().nodeId(), peer.endpoint());
					if (previous != null && (!previous.host().equals(peer.endpoint().host())
						|| previous.port() != peer.endpoint().port())) return Future.failedFuture("Conflicting candidate identity");
					eligible.add(peer.identity().nodeId());
				}
				return store.createGenesis(eligible, target);
			});
		}));
	}

	/**
	 * Installs a record authenticated as coming from the explicitly configured authority.
	 * @param genesis immutable authority record
	 * @param authenticatedAuthority TLS-authenticated sender identity
	 * @return confirmed durable installation
	 */
	public Future<Void> install(RaftGenesis genesis, String authenticatedAuthority) {
		return ContextDispatch.call(context(), () -> {
			if (!authorityId.equals(authenticatedAuthority) || !authorityId.equals(genesis.authorityId())) {
				return Future.failedFuture("Bootstrap authority authentication mismatch");
			}
			return state().compose(v -> store.installGenesis(genesis));
		});
	}
}
