package com.inqwise.leader;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

import io.vertx.core.Completable;

/** Owner-context verified routes and callback-only discovery aggregation. Never decides voting membership. */
final class RaftPeerRegistry {
	static final class ClusterMismatch extends IllegalArgumentException {
		private static final long serialVersionUID = 1L;
		final String cluster;
		ClusterMismatch(String cluster) { super("Peer cluster conflict: " + cluster); this.cluster = cluster; }
	}
	private record Verified(NodeEndpoint endpoint, long verifiedAt) {}
	private final String localId;
	private final int limit;
	private final LongSupplier clock;
	private final Map<String, Verified> routes = new HashMap<>();
	private final Set<String> quarantined = new HashSet<>();
	private long generation;
	private boolean closed;

	RaftPeerRegistry(String localId, int limit) { this(localId, limit, System::nanoTime); }
	RaftPeerRegistry(String localId, int limit, LongSupplier clock) {
		this.localId = localId; this.limit = limit; this.clock = clock;
	}

	NodeEndpoint endpoint(String id) { var route = routes.get(id); return route == null ? null : route.endpoint(); }
	List<NodeEndpoint> endpoints() { return routes.values().stream().map(Verified::endpoint).toList(); }
	boolean quarantined(String id) { return quarantined.contains(id); }

	void remember(DiscoveredPeer peer) {
		if (closed) throw new IllegalStateException("Transport closed");
		String id = peer.identity().nodeId();
		if (quarantined.contains(id)) throw new IllegalStateException("Peer identity is quarantined");
		if (!routes.containsKey(id) && routes.size() >= limit) throw new IllegalStateException("Route table is full");
		routes.put(id, new Verified(peer.endpoint(), clock.getAsLong()));
	}

	void prune(Set<String> members, long ageMillis) {
		if (ageMillis < 1) throw new IllegalArgumentException("Positive route age is required");
		long now = clock.getAsLong(), age = TimeUnit.MILLISECONDS.toNanos(ageMillis);
		routes.entrySet().removeIf(entry -> !members.contains(entry.getKey()) && now - entry.getValue().verifiedAt() >= age);
	}

	void close() { closed = true; generation++; routes.clear(); quarantined.clear(); }

	void discover(List<NodeEndpoint> endpoints, BiConsumer<NodeEndpoint, Completable<DiscoveredPeer>> probe,
		Completable<RaftClusterTransport.DiscoveryResult> completion) {
		if (closed || endpoints.size() > limit) {
			completion.fail(closed ? "Transport closed" : "Too many discovery endpoints"); return;
		}
		var distinct = endpoints.stream().distinct().toList();
		var pass = new Pass(++generation, distinct.size(), completion);
		if (distinct.isEmpty()) { pass.finish(); return; }
		for (var endpoint : distinct) {
			var contact = pass.new Contact(endpoint);
			try { probe.accept(endpoint, contact); } catch (Exception e) { contact.fail(e); }
		}
	}

	private final class Pass {
		private final long epoch;
		private int remaining;
		private Completable<RaftClusterTransport.DiscoveryResult> completion;
		private final Map<String, DiscoveredPeer> peers = new HashMap<>();
		private final Map<NodeEndpoint, String> failures = new HashMap<>();
		private final Set<String> conflicts = new HashSet<>();
		private final Set<String> clusterConflicts = new HashSet<>();

		Pass(long epoch, int remaining, Completable<RaftClusterTransport.DiscoveryResult> completion) {
			this.epoch = epoch; this.remaining = remaining; this.completion = completion;
		}

		private final class Contact implements Completable<DiscoveredPeer> {
			private final NodeEndpoint endpoint;
			private boolean completed;
			Contact(NodeEndpoint endpoint) { this.endpoint = endpoint; }
			@Override public void complete(DiscoveredPeer peer, Throwable failure) {
				if (completed) return;
				completed = true;
				if (failure != null) {
					failures.put(endpoint, String.valueOf(failure.getMessage()));
					if (failure instanceof ClusterMismatch mismatch) clusterConflicts.add(mismatch.cluster);
				} else if (!peer.identity().nodeId().equals(localId)) {
					var previous = peers.putIfAbsent(peer.identity().nodeId(), peer);
					if (previous != null && !sameAddress(previous.endpoint(), peer.endpoint())) {
						conflicts.add(peer.identity().nodeId());
						failures.put(previous.endpoint(), "Conflicting endpoints claim one identity");
						failures.put(peer.endpoint(), "Conflicting endpoints claim one identity");
					}
				}
				if (--remaining == 0) finish();
			}
		}

		void finish() {
			var callback = completion; completion = null;
			if (closed || epoch != generation) {
				peers.clear(); failures.clear(); conflicts.clear(); clusterConflicts.clear();
				callback.fail(closed ? "Transport closed" : "Discovery superseded by a newer refresh"); return;
			}
			for (String id : conflicts) { routes.remove(id); peers.remove(id); quarantined.add(id); }
			if (quarantined.size() > limit) quarantined.retainAll(conflicts);
			for (String id : List.copyOf(peers.keySet())) {
				var peer = peers.get(id);
				if (!routes.containsKey(id) && routes.size() >= limit) {
					failures.put(peer.endpoint(), "Route table is full"); peers.remove(id);
				} else {
					routes.put(id, new Verified(peer.endpoint(), clock.getAsLong())); quarantined.remove(id);
				}
			}
			var result = new RaftClusterTransport.DiscoveryResult(List.copyOf(peers.values()), failures, clusterConflicts);
			peers.clear(); failures.clear(); conflicts.clear(); clusterConflicts.clear();
			callback.succeed(result);
		}
	}

	private static boolean sameAddress(NodeEndpoint first, NodeEndpoint second) {
		return first.host().equals(second.host()) && first.port() == second.port();
	}
}
