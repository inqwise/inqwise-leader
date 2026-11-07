package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.vertx.core.Completable;
import io.vertx.core.Promise;

class RaftPeerRegistryTest {
	NodeEndpoint endpoint(String id, int port) { return new NodeEndpoint("127.0.0.1", port, id); }
	DiscoveredPeer peer(String id, int port) {
		return new DiscoveredPeer(new RaftIdentity(id, "group"), "cluster", endpoint(id, port));
	}

	@Test void discoveryResultIsIndependentOfMutableInputsAndRejectsOutputMutation() {
		var discovered = peer("node-b", 8081); var failed = endpoint("node-c", 8082);
		var peers = new java.util.ArrayList<>(List.of(discovered));
		var failures = new HashMap<>(Map.of(failed, "Unavailable"));
		var conflicts = new java.util.HashSet<>(Set.of("foreign-cluster"));
		var result = new RaftClusterTransport.DiscoveryResult(peers, failures, conflicts);
		peers.clear(); failures.clear(); conflicts.clear();
		assertEquals(List.of(discovered), result.peers());
		assertEquals(Map.of(failed, "Unavailable"), result.failures());
		assertEquals(Set.of("foreign-cluster"), result.conflicts());
		assertThrows(UnsupportedOperationException.class, () -> result.peers().clear());
		assertThrows(UnsupportedOperationException.class, () -> result.failures().clear());
		assertThrows(UnsupportedOperationException.class, () -> result.conflicts().clear());
		assertTrue(new RaftClusterTransport.DiscoveryResult(peers, failures).conflicts().isEmpty());
	}

	@Test void duplicateClaimsAreQuarantinedAndOnlyUnambiguousDiscoveryClearsThem() {
		var registry = new RaftPeerRegistry("node-a", 4);
		var first = endpoint("node-b", 8081); var second = endpoint("node-b", 8082);
		Map<NodeEndpoint, Completable<DiscoveredPeer>> contacts = new HashMap<>();
		var result = Promise.<RaftClusterTransport.DiscoveryResult>promise();
		registry.discover(List.of(first, second, first), contacts::put, result);
		assertEquals(2, contacts.size());
		contacts.get(first).succeed(peer("node-b", 8081));
		contacts.get(first).fail("duplicate callback");
		assertFalse(result.future().isComplete());
		contacts.get(second).succeed(peer("node-b", 8082));
		assertTrue(result.future().succeeded()); assertTrue(result.future().result().peers().isEmpty());
		assertEquals(2, result.future().result().failures().size());
		assertTrue(registry.quarantined("node-b")); assertNull(registry.endpoint("node-b"));
		assertThrows(IllegalStateException.class, () -> registry.remember(peer("node-b", 8081)));
		var restored = Promise.<RaftClusterTransport.DiscoveryResult>promise();
		registry.discover(List.of(first), (endpoint, completion) -> completion.succeed(peer("node-b", 8081)), restored);
		assertFalse(registry.quarantined("node-b")); assertEquals(first, registry.endpoint("node-b"));
		assertEquals(2, result.future().result().failures().size());
	}

	@Test void newerDiscoveryAndClosePreventLateCallbacksFromInstallingRoutes() {
		var registry = new RaftPeerRegistry("node-a", 4);
		var held = Promise.<Completable<DiscoveredPeer>>promise();
		var old = Promise.<RaftClusterTransport.DiscoveryResult>promise();
		registry.discover(List.of(endpoint("node-b", 8081)), (endpoint, completion) -> held.complete(completion), old);
		var fresh = Promise.<RaftClusterTransport.DiscoveryResult>promise();
		registry.discover(List.of(endpoint("node-c", 8082)), (endpoint, completion) -> completion.succeed(peer("node-c", 8082)), fresh);
		held.future().result().succeed(peer("node-b", 8081));
		assertTrue(old.future().failed()); assertTrue(old.future().cause().getMessage().contains("superseded"));
		assertNull(registry.endpoint("node-b")); assertEquals(endpoint("node-c", 8082), registry.endpoint("node-c"));
		var closing = Promise.<RaftClusterTransport.DiscoveryResult>promise();
		var last = Promise.<Completable<DiscoveredPeer>>promise();
		registry.discover(List.of(endpoint("node-d", 8083)), (endpoint, completion) -> last.complete(completion), closing);
		registry.close(); last.future().result().succeed(peer("node-d", 8083));
		assertTrue(closing.future().failed()); assertTrue(registry.endpoints().isEmpty());
		assertThrows(IllegalStateException.class, () -> registry.remember(peer("node-d", 8083)));
	}

	@Test void failedDiscoveryRetainsMembersAndPruningUsesVerificationAgeWithoutGrowingPastCapacity() {
		var clock = new AtomicLong(); var registry = new RaftPeerRegistry("node-a", 2, clock::get);
		registry.remember(peer("node-b", 8081)); registry.remember(peer("node-c", 8082));
		assertThrows(IllegalStateException.class, () -> registry.remember(peer("node-d", 8083)));
		clock.set(TimeUnit.MILLISECONDS.toNanos(20)); registry.remember(peer("node-b", 8081));
		registry.prune(Set.of("node-c"), 10); assertEquals(2, registry.endpoints().size());
		clock.set(TimeUnit.MILLISECONDS.toNanos(40)); registry.prune(Set.of("node-c"), 10);
		assertNull(registry.endpoint("node-b")); assertNotNull(registry.endpoint("node-c"));
		registry.remember(peer("node-d", 8083));
		var failed = Promise.<RaftClusterTransport.DiscoveryResult>promise();
		registry.discover(List.of(endpoint("node-c", 8082)), (endpoint, completion) -> completion.fail("DNS/contact unavailable"), failed);
		assertEquals(1, failed.future().result().failures().size()); assertEquals(2, registry.endpoints().size());
		registry.remember(peer("node-c", 9090)); assertEquals(9090, registry.endpoint("node-c").port());
		assertThrows(UnsupportedOperationException.class, () -> registry.endpoints().clear());
	}

	@Test void clusterConflictsProbeExceptionsAndLocalIdentityNeverBecomePeerRoutes() {
		var registry = new RaftPeerRegistry("node-a", 3);
		var result = Promise.<RaftClusterTransport.DiscoveryResult>promise();
		registry.discover(List.of(endpoint("node-a", 8080), endpoint("node-b", 8081), endpoint("node-c", 8082)),
			(endpoint, completion) -> {
				if (endpoint.port() == 8080) completion.succeed(peer("node-a", 8080));
				else if (endpoint.port() == 8081) completion.fail(new RaftPeerRegistry.ClusterMismatch("other-cluster"));
				else throw new IllegalStateException("probe failed");
			}, result);
		assertTrue(result.future().succeeded()); assertTrue(result.future().result().peers().isEmpty());
		assertEquals(Set.of("other-cluster"), result.future().result().conflicts());
		assertEquals(2, result.future().result().failures().size()); assertTrue(registry.endpoints().isEmpty());
		var excess = Promise.<RaftClusterTransport.DiscoveryResult>promise();
		registry.discover(java.util.Collections.nCopies(4, endpoint("node-b", 8081)),
			(endpoint, completion) -> fail("Must reject before probing"), excess);
		assertTrue(excess.future().failed());
	}
}
