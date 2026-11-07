package com.inqwise.leader;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.vertx.core.dns.DnsClientOptions;
import io.vertx.core.json.JsonObject;

class DiscoveryOptionsTest {
	@Test void staticContactsAreImmutableAndDefaultTo8080() {
		var discovery = DiscoveryOptions.staticEndpoints("127.0.0.1#node-a", "127.0.0.2:9000#node-b",
			"127.0.0.1:8080#node-a");
		assertEquals(List.of(new NodeEndpoint("127.0.0.1", 8080, "node-a"),
			new NodeEndpoint("127.0.0.2", 9000, "node-b")), discovery.endpoints());
		assertThrows(UnsupportedOperationException.class, () -> discovery.endpoints().clear());
		var input = new ArrayList<>(discovery.endpoints()); var copied = new DiscoveryOptions.Static(input);
		input.clear(); discovery.toJson().getJsonArray("endpoints").clear();
		assertEquals(discovery, copied);
		assertEquals(discovery, DiscoveryOptions.fromJson(discovery.toJson()));
	}

	@Test void srvDefensivelyCopiesStandardVertxDnsOptionsAndRoundTripsJson() {
		var dns = new DnsClientOptions().setHost("127.0.0.1").setPort(5353).setQueryTimeout(500);
		var discovery = DiscoveryOptions.srv("_leader._tcp.example.net", dns);
		dns.setPort(1234); discovery.dnsOptions().setHost("192.0.2.1");
		discovery.toJson().getJsonObject("dns").put("port", 9876);
		assertEquals(5353, discovery.dnsOptions().getPort());
		assertEquals("127.0.0.1", discovery.dnsOptions().getHost());
		assertEquals(discovery.toJson(), DiscoveryOptions.fromJson(discovery.toJson()).toJson());
		assertEquals(1000, DiscoveryOptions.srv("_leader._tcp.test").dnsOptions().getQueryTimeout());
		var options = LeaderOptions.builder().groupName("group").bootstrapAuthorityId("node-a")
			.discovery(discovery).build();
		assertInstanceOf(DiscoveryOptions.Srv.class, options.getDiscovery());
		assertEquals(discovery.toJson(), LeaderOptions.builder(options.toJson()).build().getDiscovery().toJson());
	}

	@Test void variantsRejectMixedFieldsAndInvalidDirectConfiguration() {
		assertThrows(IllegalArgumentException.class, () -> DiscoveryOptions.fromJson(new JsonObject()
			.put("type", "static").put("name", "_leader._tcp.test")));
		assertThrows(IllegalArgumentException.class, () -> DiscoveryOptions.fromJson(new JsonObject()
			.put("type", "srv").put("name", "_leader._tcp.test").put("endpoints", List.of())));
		assertThrows(IllegalArgumentException.class, () -> DiscoveryOptions.staticEndpoints("localhost"));
		assertThrows(IllegalArgumentException.class, () -> DiscoveryOptions.staticEndpoints(
			java.util.Collections.nCopies(65, "127.0.0.1").toArray(String[]::new)));
		assertThrows(IllegalArgumentException.class, () -> DiscoveryOptions.srv("http://example.net"));
		assertThrows(IllegalArgumentException.class, () -> DiscoveryOptions.srv("_leader._tcp.test",
			new DnsClientOptions().setQueryTimeout(6000)));
	}
}
