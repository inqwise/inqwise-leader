package com.inqwise.leader;

import java.util.List;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MultiMapConfig;
import com.hazelcast.config.SplitBrainProtectionConfig;
import com.hazelcast.splitbrainprotection.SplitBrainProtectionOn;

/** Optional provider fixture configuration, never used by the production library. */
final class ClusterElectionTestConfig {
	static final String PROTECTION = "election-majority";

	private ClusterElectionTestConfig() {}

	static Config create(String clusterName, int port, List<Integer> ports, boolean protectedMap) {
		var config = new Config().setClusterName(clusterName);
		config.setProperty("hazelcast.logging.type", "log4j2");
		config.setProperty("hazelcast.shutdownhook.enabled", "false");
		config.setProperty("hazelcast.phone.home.enabled", "false");
		config.setProperty("hazelcast.operation.thread.count", "2");
		config.setProperty("hazelcast.operation.generic.thread.count", "2");
		config.setProperty("hazelcast.io.input.thread.count", "1");
		config.setProperty("hazelcast.io.output.thread.count", "1");
		config.setProperty("hazelcast.event.thread.count", "2");
		config.setProperty("hazelcast.partition.count", "17");
		config.setProperty("hazelcast.wait.seconds.before.join", "0");
		config.setProperty("hazelcast.max.no.heartbeat.seconds", "5");
		config.setProperty("hazelcast.heartbeat.interval.seconds", "1");
		config.getMetricsConfig().setEnabled(false);
		config.addMultiMapConfig(new MultiMapConfig("__vertx.subs")
			.setBackupCount(1).setValueCollectionType(MultiMapConfig.ValueCollectionType.SET));
		if (protectedMap) {
			// Keep test recovery bounded if failure detection temporarily produces several views.
			config.setProperty("hazelcast.merge.first.run.delay.seconds", "5");
			config.setProperty("hazelcast.merge.next.run.delay.seconds", "5");
			config.addSplitBrainProtectionConfig(new SplitBrainProtectionConfig(PROTECTION, true, 2)
				.setProtectOn(SplitBrainProtectionOn.READ_WRITE));
			config.addMapConfig(new MapConfig(LeaderConsensus.MAP_NAME).setSplitBrainProtectionName(PROTECTION));
		}
		var network = config.getNetworkConfig().setPort(port).setPortAutoIncrement(false);
		network.setPublicAddress("127.0.0.1:" + port);
		network.getInterfaces().setEnabled(true).addInterface("127.0.0.1");
		var join = network.getJoin();
		join.getMulticastConfig().setEnabled(false);
		join.getAutoDetectionConfig().setEnabled(false);
		var seeds = join.getTcpIpConfig().setEnabled(true).setConnectionTimeoutSeconds(1);
		for (int seed : ports) seeds.addMember("127.0.0.1:" + seed);
		return config;
	}
}
