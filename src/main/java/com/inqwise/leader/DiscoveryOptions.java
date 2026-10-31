package com.inqwise.leader;

import static com.inqwise.leader.OptionsJson.*;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.vertx.core.dns.DnsClientOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/** Immutable discovery configuration; resolution is performed by {@link NodeDiscovery}. */
public sealed interface DiscoveryOptions permits DiscoveryOptions.Static, DiscoveryOptions.Srv {
	/**
	 * @return independent JSON configuration
	 */
	JsonObject toJson();

	/**
	 * Configures IPv4 contacts, with omitted ports defaulting to 8080 independently of the local listener.
	 * @param endpoints contacts in IPv4[:port][#expectedNodeId] form
	 * @return immutable static configuration
	 */
	static Static staticEndpoints(String... endpoints) {
		Objects.requireNonNull(endpoints, "endpoints");
		return new Static(Arrays.stream(endpoints).map(NodeEndpoint::parse).toList());
	}

	/**
	 * @param name TCP SRV name
	 * @return immutable SRV configuration with a one-second DNS timeout
	 */
	static Srv srv(String name) { return srv(name, new DnsClientOptions().setQueryTimeout(1000)); }

	/**
	 * @param name TCP SRV name
	 * @param dnsOptions standard Vert.x DNS options, copied
	 * @return SRV configuration
	 */
	static Srv srv(String name, DnsClientOptions dnsOptions) { return new Srv(name, dnsOptions); }

	/**
	 * @param json static/SRV JSON, copied
	 * @return validated typed discovery configuration
	 */
	static DiscoveryOptions fromJson(JsonObject json) {
		Objects.requireNonNull(json, "json");
		return switch (text(json, "type", "static")) {
			case "static" -> {
				checkKeys(json, Set.of("type", "endpoints"));
				Object value = json.getValue("endpoints", new JsonArray());
				if (!(value instanceof JsonArray endpoints)) throw new IllegalArgumentException("endpoints must be an array");
				yield staticEndpoints(endpoints.stream().map(endpoint -> {
					if (!(endpoint instanceof String text)) throw new IllegalArgumentException("endpoints must be strings");
					return text;
				}).toArray(String[]::new));
			}
			case "srv" -> {
				checkKeys(json, Set.of("type", "name", "dns"));
				yield srv(text(json, "name", null), parseDns(object(json, "dns")));
			}
			default -> throw new IllegalArgumentException("discovery.type must be static or srv");
		};
	}

	/**
	 * Static peer contacts, never a declaration of the voting set.
	 * @param endpoints immutable contacts
	 */
	record Static(List<NodeEndpoint> endpoints) implements DiscoveryOptions {
		/**
		 * @param endpoints contacts, copied and deduplicated; at most 64 before deduplication
		 */
		public Static {
			if (endpoints.size() > 64) throw new IllegalArgumentException("At most 64 static endpoints are supported");
			endpoints = List.copyOf(endpoints).stream().distinct().toList();
		}
		@Override public JsonObject toJson() {
			var values = new JsonArray();
			for (var endpoint : endpoints) values.add(endpoint.host() + ":" + endpoint.port()
				+ (endpoint.expectedNodeId() == null ? "" : "#" + endpoint.expectedNodeId()));
			return new JsonObject().put("type", "static").put("endpoints", values);
		}
	}

	/** SRV contact source using standard Vert.x DNS configuration. */
	final class Srv implements DiscoveryOptions {
		private final String name;
		private final DnsClientOptions dnsOptions;
		private Srv(String name, DnsClientOptions dnsOptions) {
			this.name = Objects.requireNonNull(name, "name");
			if (!name.matches("_[A-Za-z0-9-]+\\._tcp\\.[A-Za-z0-9.-]+\\.?")) {
				throw new IllegalArgumentException("Invalid TCP SRV name");
			}
			this.dnsOptions = parseDns(Objects.requireNonNull(dnsOptions, "dnsOptions").toJson());
		}
		/**
		 * @return TCP SRV name
		 */
		public String name() { return name; }
		/**
		 * @return independent standard Vert.x DNS options
		 */
		public DnsClientOptions dnsOptions() { return new DnsClientOptions(dnsOptions); }
		@Override public JsonObject toJson() {
			return new JsonObject().put("type", "srv").put("name", name).put("dns", dnsOptions.toJson());
		}
	}

	private static DnsClientOptions parseDns(JsonObject dns) {
		checkKeys(dns, Set.of("host", "port", "queryTimeout", "logActivity", "activityLogFormat", "recursionDesired",
			"hostResolutionRetryDelay"));
		if (dns.containsKey("host")) text(dns, "host", null);
		if (dns.containsKey("port") && integer(dns, "port", -1, -1, 65535) == 0) {
			throw new IllegalArgumentException("DNS port must be -1 (Vert.x default) or 1..65535");
		}
		if (dns.containsKey("activityLogFormat")) text(dns, "activityLogFormat", null);
		if (dns.containsKey("hostResolutionRetryDelay")) integer(dns, "hostResolutionRetryDelay", 0, 0, Long.MAX_VALUE);
		long timeout = integer(dns, "queryTimeout", 1000, 1, 5000);
		for (String flag : List.of("logActivity", "recursionDesired")) {
			if (dns.containsKey(flag) && !(dns.getValue(flag) instanceof Boolean)) {
				throw new IllegalArgumentException("DNS " + flag + " must be boolean");
			}
		}
		return new DnsClientOptions(dns).setQueryTimeout(timeout);
	}
}
