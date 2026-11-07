package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;


import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.eventbus.EventBusOptions;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;

/** Optional real-provider tests; no provider types are used by the production library. */
@Timeout(120)
class LeaderConsensusClusterIT {
	private final String clusterName = "leader-election-it-" + UUID.randomUUID();
	private final Vertx[] members = new Vertx[3];
	private final HazelcastClusterManager[] managers = new HazelcastClusterManager[3];
	private final List<LeaderConsensus> elections = new ArrayList<>();
	private final int[] ports = new int[3];

	@BeforeEach void startCluster() throws Exception {
		// Hold all reservations together to avoid accidentally selecting a port twice.
		var reservations = new ArrayList<ServerSocket>();
		try {
			for (int i = 0; i < ports.length; i++) {
				var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
				reservations.add(socket); ports[i] = socket.getLocalPort();
			}
		} finally {
			for (var socket : reservations) socket.close();
		}
		openCluster();
	}

	private void openCluster() throws Exception {
		var starts = new ArrayList<Future<Vertx>>();
		for (int i = 0; i < members.length; i++) starts.add(openMember(i));
		await(Future.join(starts));
		until(() -> Arrays.stream(managers).allMatch(manager -> manager.getNodes().size() == 3),
			"all three Vert.x members must see the same cluster");
	}

	private Future<Vertx> openMember(int index) {
		var config = ClusterElectionTestConfig.create(clusterName, ports[index],
			Arrays.stream(ports).boxed().toList(), false);
		var manager = new HazelcastClusterManager(config);
		managers[index] = manager;
		var options = new VertxOptions().setEventLoopPoolSize(2).setWorkerPoolSize(4)
			.setInternalBlockingPoolSize(2)
			.setEventBusOptions(new EventBusOptions().setHost("127.0.0.1").setPort(0));
		return Vertx.builder().with(options).withClusterManager(manager).buildClustered()
			.onSuccess(vertx -> members[index] = vertx);
	}

	@AfterEach void shutdown() throws Exception { closeCluster(); }

	private void closeCluster() throws Exception {
		elections.forEach(LeaderConsensus::stop); elections.clear();
		var closing = Arrays.stream(members).filter(java.util.Objects::nonNull).map(Vertx::close).toList();
		try { await(Future.join(closing)); }
		finally {
			for (var manager : managers) {
				if (manager != null && manager.getHazelcastInstance() != null
					&& manager.getHazelcastInstance().getLifecycleService().isRunning()) {
					manager.getHazelcastInstance().getLifecycleService().terminate();
				}
			}
			Arrays.fill(members, null); Arrays.fill(managers, null);
		}
	}

	private LeaderConsensus election(int index, String group) {
		var options = LeaderConsensusOptions.builder().withLeaderCycleMsgTime(100L)
			.withPendingToLeaderMsgTime(1500L).build();
		var election = new LeaderConsensus(group, members[index], options);
		elections.add(election); election.start(); return election;
	}

	private List<LeaderConsensus> group(String name) {
		var group = new ArrayList<LeaderConsensus>();
		for (int i = 0; i < members.length; i++) group.add(election(i, name));
		return group;
	}

	@Test void independentVertxInstancesShareOwnershipAndGroupsFailOverIndependently() throws Exception {
		var first = group("first"); var second = group("second");
		until(() -> leaders(first) == 1 && leaders(second) == 1, "one owner per group");
		maintain(() -> leaders(first) == 1 && leaders(second) == 1, 1000);
		String record = await(records(0).compose(map -> map.get("first")));
		assertNotNull(record);
		// A locally scoped map on each runtime would elect three owners instead.
		for (int i = 1; i < members.length; i++) {
			assertNotNull(await(records(i).compose(map -> map.get("first"))));
		}
		var unaffected = second.stream().filter(LeaderConsensus::getIsLeader).findFirst().orElseThrow();
		var stopped = first.stream().filter(LeaderConsensus::getIsLeader).findFirst().orElseThrow();
		stopped.stop(); assertFalse(stopped.getIsLeader());
		until(() -> leaders(first) == 1, "first group must fail over");
		maintain(() -> unaffected.getIsLeader() && leaders(second) == 1, 700);
	}

	@Test void terminatedMemberLosesReadinessAndAReplacementJoinsWithoutStoredIdentity() throws Exception {
		var group = group("replace");
		until(() -> leaders(group) == 1, "initial owner");
		int failed = 0;
		while (!group.get(failed).getIsLeader()) failed++;
		var previous = group.get(failed);
		String previousNodeId = managers[failed].getNodeId();
		// Keep its Vert.x instance alive: no election.stop() or record cleanup before the fault.
		managers[failed].getHazelcastInstance().getLifecycleService().terminate();
		until(() -> !previous.getIsLeader(), "terminated member must expire locally");
		until(() -> leaders(group) == 1, "surviving members must elect an owner");
		var surviving = group.stream().filter(LeaderConsensus::getIsLeader).findFirst().orElseThrow();
		previous.stop(); elections.remove(previous);
		await(members[failed].close()); members[failed] = null;
		await(openMember(failed));
		assertNotEquals(previousNodeId, managers[failed].getNodeId());
		var replacement = election(failed, "replace"); group.set(failed, replacement);
		maintain(() -> surviving.getIsLeader() && !replacement.getIsLeader() && leaders(group) == 1, 2000);
	}

	@Test void completeClusterRestartStartsFreshWithOnlyTheSameGroupName() throws Exception {
		var original = group("fresh");
		until(() -> leaders(original) == 1, "initial owner");
		String previous = await(records(0).compose(map -> map.get("fresh")));
		closeCluster(); openCluster();
		assertNull(await(records(0).compose(map -> map.get("fresh"))), "no durable election record is required");
		var restarted = group("fresh");
		until(() -> leaders(restarted) == 1, "owner after complete cluster restart");
		assertNotEquals(previous, await(records(0).compose(map -> map.get("fresh"))));
		maintain(() -> leaders(restarted) == 1, 700);
	}

	private Future<AsyncMap<String, String>> records(int index) {
		return members[index].sharedData().getAsyncMap(LeaderConsensus.MAP_NAME);
	}

	private static long leaders(List<LeaderConsensus> group) {
		return group.stream().filter(LeaderConsensus::getIsLeader).count();
	}

	private static <T> T await(Future<T> future) throws Exception {
		return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
	}

	private static void until(BooleanSupplier condition, String description) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() >= deadline) fail("Timed out: " + description);
			Thread.sleep(20);
		}
	}

	private static void maintain(BooleanSupplier condition, long millis) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
		do {
			assertTrue(condition.getAsBoolean(), "cluster ownership became inconsistent");
			Thread.sleep(20);
		} while (System.nanoTime() < deadline);
	}
}
