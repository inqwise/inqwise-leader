package com.inqwise.leader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.hazelcast.instance.impl.HazelcastInstanceProxy;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.eventbus.EventBusOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;

/** Test-only child process. Stdin controls and provider network faults are not production APIs. */
public final class ClusterElectionProcessNode {
	static final String PREFIX = "CLUSTER_ELECTION_PROCESS ";

	public static void main(String[] args) throws Exception {
		var settings = new JsonObject(Files.readString(Path.of(args[0])));
		var seeds = settings.getJsonArray("ports").stream().map(value -> ((Number) value).intValue()).toList();
		boolean protectedMap = settings.getBoolean("protectedMap");
		var config = ClusterElectionTestConfig.create(settings.getString("cluster"), settings.getInteger("port"),
			seeds, protectedMap);
		var manager = new HazelcastClusterManager(config);
		Vertx vertx = null; LeaderConsensus election = null;
		try {
			vertx = await(Vertx.builder().with(new VertxOptions().setEventLoopPoolSize(2).setWorkerPoolSize(4)
				.setInternalBlockingPoolSize(2)
				.setEventBusOptions(new EventBusOptions().setHost("127.0.0.1").setPort(0)))
				.withClusterManager(manager).buildClustered());
			election = new LeaderConsensus(settings.getString("group"), vertx,
				LeaderConsensusOptions.builder().withLeaderCycleMsgTime(100L).withPendingToLeaderMsgTime(1500L).build());
			election.start();
			emit(new JsonObject().put("event", "ready").put("nodeId", manager.getNodeId()));
			try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
				String line;
				while ((line = input.readLine()) != null) {
					var request = new JsonObject(line);
					try {
						String operation = request.getString("operation");
						if (operation.equals("isolate")) {
							// Stop inbound/outbound Hazelcast networking, not the member engine or application.
							server(manager).stop();
						}
						if (operation.equals("heal")) server(manager).start();
						JsonObject result;
						if (operation.equals("read-owner")) {
							String record = await(vertx.sharedData().<String, String>getAsyncMap(LeaderConsensus.MAP_NAME)
								.compose(map -> map.get(settings.getString("group"))));
							result = new JsonObject().put("record", record);
						} else if (operation.equals("status") || operation.equals("isolate") || operation.equals("heal")) {
							result = new JsonObject().put("leader", election.getIsLeader()).put("nodeId", manager.getNodeId())
								.put("members", manager.getNodes().size())
								.put("providerRunning", manager.getHazelcastInstance().getLifecycleService().isRunning())
								.put("networkLive", server(manager).isLive());
							if (protectedMap) result.put("protectionPresent", manager.getHazelcastInstance()
								.getSplitBrainProtectionService().getSplitBrainProtection(ClusterElectionTestConfig.PROTECTION)
								.hasMinimumSize());
						} else throw new IllegalArgumentException("Unknown test operation");
						emit(new JsonObject().put("id", request.getLong("id")).put("ok", true).put("result", result));
					} catch (Exception e) {
						Throwable cause = e;
						while (cause.getCause() != null) cause = cause.getCause();
						emit(new JsonObject().put("id", request.getLong("id")).put("ok", false)
							.put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage()));
					}
				}
			}
		} catch (Exception e) {
			emit(new JsonObject().put("event", "failed").put("error", e.toString()));
			throw e;
		} finally {
			if (election != null) election.stop();
			try {
				if (vertx != null) await(vertx.close());
			} finally {
				if (manager.getHazelcastInstance() != null) {
					manager.getHazelcastInstance().getLifecycleService().terminate();
				}
			}
		}
	}

	private static com.hazelcast.internal.server.Server server(HazelcastClusterManager manager) {
		// Internal dependency is isolated to this pinned, optional provider test fixture.
		return ((HazelcastInstanceProxy) manager.getHazelcastInstance()).getOriginal().node.getServer();
	}

	private static <T> T await(Future<T> future) throws Exception {
		return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
	}

	private static void emit(JsonObject result) { System.out.println(PREFIX + result.encode()); System.out.flush(); }
}
