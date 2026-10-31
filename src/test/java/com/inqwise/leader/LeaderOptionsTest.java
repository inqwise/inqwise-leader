package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

class LeaderOptionsTest {
	static java.util.List<NodeEndpoint> contacts(LeaderOptions options) {
		return ((DiscoveryOptions.Static) options.getDiscovery()).endpoints();
	}
	static JsonObject minimal() {
		return new JsonObject().put("groupName", "scheduled-job").put("bootstrapAuthorityId", "node-a");
	}
	@Test void builderReuseAndDerivedOptionsCannotMutatePreviouslyBuiltConfiguration() {
		var discovery = new JsonObject().put("type", "static")
			.put("endpoints", new JsonArray().add("127.0.0.1"));
		var membership = new JsonObject().put("maxLearners", 2);
		var builder = LeaderOptions.builder().groupName("group").bootstrapAuthorityId("node-a")
			.discovery(DiscoveryOptions.fromJson(discovery)).membership(membership);
		discovery.getJsonArray("endpoints").clear(); membership.put("maxLearners", 3);
		var first = builder.build();
		var second = builder.membership(new JsonObject().put("maxLearners", 4)).build();
		assertEquals(2, first.getMembershipOptions().maxLearners());
		assertEquals(1, contacts(first).size());
		assertEquals(4, second.getMembershipOptions().maxLearners());
		var derived = first.toBuilder().groupName("other").build();
		assertEquals("group", first.getGroupName()); assertEquals("other", derived.getGroupName());
		assertThrows(IllegalArgumentException.class, () -> builder.targetVoterCount(0).build());
		assertEquals(4, second.getMembershipOptions().maxLearners());
	}

	@Test void publicCreationIsExclusivelyThroughBuilderAndValidationHappensAtBuild() {
		assertEquals(0, LeaderOptions.class.getConstructors().length);
		assertEquals(0, LeaderOptions.Builder.class.getConstructors().length);
		assertTrue(java.util.Arrays.stream(LeaderOptions.class.getMethods())
			.noneMatch(method -> method.getName().startsWith("set")));
		var builder = LeaderOptions.builder();
		assertThrows(NullPointerException.class, builder::build);
		builder.groupName("group");
		assertThrows(NullPointerException.class, builder::build);
		assertEquals("group", builder.bootstrapAuthorityId("node-a").build().getGroupName());
		var invalidJson = LeaderOptions.builder(minimal().put("listenPrt", 8080));
		assertThrows(IllegalArgumentException.class, invalidJson::build);
	}

	@Test void defaultsHaveOnlyRequiredDeploymentIdentityFields() {
		var options = LeaderOptions.builder(minimal()).build();
		assertFalse(options.toJson().containsKey("listenHost")); assertFalse(options.toJson().containsKey("listenPort"));
		assertEquals(3, options.getTargetVoterCount()); assertNull(options.getNodeId());
		assertNull(options.getDataDirectory()); assertEquals(30000, options.getLeaveTimeoutMillis());
		assertEquals(RaftElection.Timing.defaults(), options.getTiming());
		assertEquals(MembershipController.Options.defaults(), options.getMembershipOptions());
		assertTrue(contacts(options).isEmpty());
		assertEquals(options.toJson(), LeaderOptions.builder(options.toJson()).build().toJson());
		assertEquals(options.toJson(), LeaderOptions.builder(new JsonObject(options.toJson().encode())).build().toJson());
	}
	@Test void endpointsUseFixedDefaultPortAndKeepExplicitPortsAndIdentityConstraints() {
		var options = LeaderOptions.builder(minimal().put("nodeId", "node-a")
			.put("advertisedEndpoint", "192.0.2.10#node-a")
			.put("discovery", new JsonObject().put("type", "static").put("endpoints", new JsonArray()
				.add("192.0.2.10#node-a").add("192.0.2.11:9010#node-b").add("192.0.2.10#node-a")))).build();
		assertEquals(List.of(new NodeEndpoint("192.0.2.10", 8080, "node-a"),
			new NodeEndpoint("192.0.2.11", 9010, "node-b")), contacts(options));
		assertEquals(new NodeEndpoint("192.0.2.10", 8080, "node-a"), options.getMembershipOptions().advertisedEndpoint());
	}
	@Test void copiesAndJsonExportsDoNotShareNestedMutableState() {
		var input = minimal().put("discovery", new JsonObject().put("type", "static")
			.put("endpoints", new JsonArray().add("127.0.0.1")));
		var options = LeaderOptions.builder(input).build(); var copy = options.toBuilder().build();
		input.getJsonObject("discovery").getJsonArray("endpoints").clear();
		options.getDiscovery().toJson().getJsonArray("endpoints").clear();
		options.toJson().getJsonObject("discovery").getJsonArray("endpoints").clear();
		var changed = options.toBuilder().groupName("changed").build();
		assertEquals("changed", changed.getGroupName());
		assertEquals(1, contacts(copy).size());
		assertEquals(1, contacts(options).size());
	}
	@Test void namedNodesAutomaticallyHaveDistinctStableDirectoriesWithoutAnOverride() {
		var group = LeaderOptions.builder(minimal()).build();
		var a = group.toBuilder().nodeId("node-a").build(); var b = group.toBuilder().nodeId("node-b").build();
		assertNull(a.getDataDirectory()); assertNull(b.getDataDirectory());
		assertEquals(group.resolvedDataDirectory().resolve("nodes"), a.resolvedDataDirectory().getParent());
		assertTrue(a.resolvedDataDirectory().getFileName().toString().matches("[a-f0-9]{64}"));
		assertNotEquals(a.resolvedDataDirectory(), group.toBuilder().nodeId("NODE-A").build().resolvedDataDirectory());
		assertNotEquals(a.resolvedDataDirectory(), b.resolvedDataDirectory());
		assertEquals(a.resolvedDataDirectory(), a.toBuilder().build().resolvedDataDirectory());
	}

	@Test void defaultDirectoryIsStableAndCannotTraverseThroughGroupName() {
		var first = LeaderOptions.builder(minimal().put("groupName", "../../scheduled / 雨")).build();
		var second = first.toBuilder().build();
		assertEquals(first.resolvedDataDirectory(), second.resolvedDataDirectory());
		assertEquals(Path.of(".inqwise-leader").toAbsolutePath(), first.resolvedDataDirectory().getParent());
		assertTrue(first.resolvedDataDirectory().getFileName().toString().matches("[a-f0-9]{64}"));
		assertNotEquals(first.resolvedDataDirectory(), LeaderOptions.builder(minimal()).build().resolvedDataDirectory());
		first = first.toBuilder().dataDirectory("/private/tmp/leader-options/../node-a").build();
		assertEquals(Path.of("/private/tmp/node-a"), first.resolvedDataDirectory());
	}
	@ParameterizedTest @ValueSource(ints = {1, 3, 5, 7, 9, 63})
	void supportsAllDocumentedOddTargets(int target) {
		assertEquals(target, LeaderOptions.builder(minimal().put("targetVoterCount", target)).build().getTargetVoterCount());
	}
	@Test void advancedTimingMembershipAndStandardDnsOptionsRoundTrip() {
		var options = LeaderOptions.builder().groupName("group").bootstrapAuthorityId("node-a")
			.timing(new RaftElection.Timing(500, 1000, 100))
			.membership(new JsonObject().put("refreshIntervalMillis", 50).put("replacementDelayMillis", 900).put("maxLearners", 3))
			.discovery(DiscoveryOptions.fromJson(new JsonObject().put("type", "srv").put("name", "_leader._tcp.example.net")
				.put("dns", new JsonObject().put("host", "127.0.0.1").put("port", 5353).put("queryTimeout", 500))))
			.build();
		var restored = LeaderOptions.builder(options.toJson()).build();
		assertEquals(options.getTiming(), restored.getTiming()); assertEquals(options.getMembershipOptions(), restored.getMembershipOptions());
		assertEquals(5353, ((DiscoveryOptions.Srv) restored.getDiscovery()).dnsOptions().getPort()); assertEquals(500, ((DiscoveryOptions.Srv) restored.getDiscovery()).dnsOptions().getQueryTimeout());
		assertEquals("127.0.0.1", ((DiscoveryOptions.Srv) restored.getDiscovery()).dnsOptions().getHost());
		var frameworkDefaults = new io.vertx.core.dns.DnsClientOptions();
		var withDefaults = LeaderOptions.builder(minimal().put("discovery", new JsonObject().put("type", "srv")
			.put("name", "_leader._tcp.test").put("dns", frameworkDefaults.toJson()))).build();
		assertEquals(frameworkDefaults.toJson(), ((DiscoveryOptions.Srv) withDefaults.getDiscovery()).dnsOptions().toJson());
	}
	static Stream<JsonObject> invalid() {
		return Stream.of(
			minimal().put("targetVoterCount", 0), minimal().put("targetVoterCount", 2), minimal().put("targetVoterCount", 65),
			minimal().put("listenPort", 0), minimal().put("listenPort", 65536), minimal().put("listenPort", 8080.5),
			minimal().put("listenPort", "8080"),
			minimal().put("discovery", new JsonObject().put("type", "srv").put("name", "_leader._tcp.test")
				.put("dns", new JsonObject().putNull("queryTimeout"))),
			minimal().put("discovery", new JsonObject().put("type", "srv").put("name", "_leader._tcp.test")
				.put("dns", new JsonObject().put("queryTimeout", 1000.5))),
			minimal().put("discovery", new JsonObject().put("type", "srv").put("name", "_leader._tcp.test")
				.put("dns", new JsonObject().put("port", "53"))), minimal().putNull("listenHost"), minimal().put("listenHost", "::1"),
			minimal().put("listenHost", "localhost"), minimal().put("listenHost", "0.0.0.0"),
			minimal().put("nodeId", "node-a").put("advertisedEndpoint", "127.0.0.1#node-b"),
			minimal().put("listenPrt", 8080), minimal().put("bootstrapAuthority", true), minimal().put("leaveTimeoutMillis", -1),
			minimal().put("timing", new JsonObject().put("heartbeatMillis", 2500)),
			minimal().put("timing", new JsonObject().put("heartbeatMillis", 10.5)),
			minimal().put("membership", new JsonObject().put("maxLearners", 17)),
			minimal().put("membership", new JsonObject().put("replacementDelayMillis", 10)),
			minimal().put("discovery", "static"), minimal().put("discovery", new JsonObject().put("type", "http")),
			minimal().put("discovery", new JsonObject().put("type", "static").put("endpoints", new JsonArray().add(123))),
			minimal().put("discovery", new JsonObject().put("type", "srv").put("name", "http://example.net")),
			minimal().put("discovery", new JsonObject().put("type", "srv").put("name", "_leader._tcp.test").put("dns",
				new JsonObject().put("queryTimeout", 6000))),
			minimal().put("discovery", new JsonObject().put("type", "static").put("endpoints", new JsonArray().add("[::1]:8080"))),
			minimal().put("discovery", new JsonObject().put("type", "static").put("endpoints", new JsonArray(
				java.util.Collections.nCopies(65, "127.0.0.1")))));
	}
	@ParameterizedTest @MethodSource("invalid")
	void rejectsInvalidJsonBeforeResourceCreation(JsonObject json) {
		assertThrows(IllegalArgumentException.class, () -> LeaderOptions.builder(json).build());
	}
	@Test void requiredFieldsAndOptionalResetsAreExplicit() {
		assertThrows(NullPointerException.class, () -> LeaderOptions.builder(new JsonObject()).build());
		var builder = LeaderOptions.builder(minimal()).nodeId("node-a").dataDirectory("/private/tmp/a")
			.advertisedEndpoint("127.0.0.1");
		var options = builder.nodeId(null).dataDirectory(null).advertisedEndpoint(null).build();
		assertNull(options.getNodeId()); assertNull(options.getDataDirectory()); assertNull(options.getAdvertisedEndpoint());
	}
}
