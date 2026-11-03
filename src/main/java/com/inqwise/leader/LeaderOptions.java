package com.inqwise.leader;

import static com.inqwise.leader.OptionsJson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

import io.vertx.core.json.JsonObject;

/**
 * Immutable options for the opt-in Raft coordinator, created only through {@link Builder}.
 * Build with required group/authority fields before passing to {@link LeaderCoordinator#create}.
 * Local options never replace durable genesis.
 */
public final class LeaderOptions {
	private static final Set<String> KEYS = Set.of("groupName", "bootstrapAuthorityId", "nodeId", "dataDirectory",
		"advertisedEndpoint", "targetVoterCount", "discovery", "timing", "membership",
		"leaveTimeoutMillis", "transport");
	private final JsonObject json;
	private final DiscoveryOptions discovery;

	private LeaderOptions(JsonObject json) {
		this.json = json.copy();
		this.discovery = DiscoveryOptions.fromJson(object(this.json, "discovery"));
		validate();
	}

	/**
	 * Creates a builder with three voters, static discovery and standard timing defaults.
	 * @return new builder; groupName and bootstrapAuthorityId must be supplied before build
	 */
	public static Builder builder() { return new Builder(); }

	/**
	 * Creates a builder from JSON, with omitted fields taking their defaults. Validation occurs at build.
	 * @param json configuration, copied including nested objects
	 * @return new builder
	 */
	public static Builder builder(JsonObject json) { return new Builder(json); }

	/**
	 * Creates an independent builder initialized from these options.
	 * @return new builder
	 */
	public Builder toBuilder() { return new Builder(json); }

	/**
	 * Copies JSON configuration; mutations to the result cannot change these options.
	 * @return independent configuration including top-level defaults
	 */
	public JsonObject toJson() { return json.copy(); }

	/**
	 * Selects node communication without changing durable cluster membership.
	 * @return "tcp" (default) or "http"; all peers must use the same transport
	 */
	public String getTransport() {
		String value = optionalText(json, "transport");
		return value == null ? "tcp" : value;
	}
	/**
	 * Reads this configuration value.
	 * @return configured consensus group
	 */
	public String getGroupName() { return optionalText(json, "groupName"); }
	/**
	 * Reads this configuration value.
	 * @return designated persisted authority identity
	 */
	public String getBootstrapAuthorityId() { return optionalText(json, "bootstrapAuthorityId"); }
	/**
	 * Reads this configuration value.
	 * @return expected local identity, or null to recover/generate it through the store
	 */
	public String getNodeId() { return optionalText(json, "nodeId"); }
	/**
	 * Reads this configuration value.
	 * @return explicit persistent path, or null for the group-derived default
	 */
	public String getDataDirectory() { return optionalText(json, "dataDirectory"); }
	/**
	 * Reads this configuration value.
	 * @return initial odd voter target; an existing genesis always takes precedence
	 */
	public int getTargetVoterCount() { return (int) integer(json, "targetVoterCount", 3, 1, 63); }
	/**
	 * Reads this configuration value.
	 * @return optional concrete callback endpoint text
	 */
	public String getAdvertisedEndpoint() { return optionalText(json, "advertisedEndpoint"); }
	/**
	 * Reads this configuration value.
	 * @return immutable static/SRV discovery configuration
	 */
	public DiscoveryOptions getDiscovery() { return discovery; }
	/**
	 * Reads this configuration value.
	 * @return graceful membership deadline in milliseconds; cleanup may take longer
	 */
	public long getLeaveTimeoutMillis() { return integer(json, "leaveTimeoutMillis", 30000, 1, Long.MAX_VALUE); }
	/**
	 * Reads validated randomized election/heartbeat timing.
	 * @return timing, using defaults for omitted fields
	 */
	public RaftElection.Timing getTiming() {
		var timing = object(json, "timing"); checkKeys(timing, Set.of("electionMinMillis", "electionMaxMillis", "heartbeatMillis"));
		return new RaftElection.Timing(integer(timing, "electionMinMillis", 2500, 1, Long.MAX_VALUE),
			integer(timing, "electionMaxMillis", 5000, 1, Long.MAX_VALUE),
			integer(timing, "heartbeatMillis", 1000, 1, Long.MAX_VALUE));
	}
	/**
	 * Reads controller bounds and the resolved advertised endpoint.
	 * Omitted advertised ports default to 8080 here; the coordinator resolves them against its selected listening port.
	 * @return validated controller options
	 */
	public MembershipController.Options getMembershipOptions() {
		return getMembershipOptions(NodeEndpoint.DEFAULT_PORT);
	}

	MembershipController.Options getMembershipOptions(int advertisedPort) {
		var membership = object(json, "membership");
		checkKeys(membership, Set.of("refreshIntervalMillis", "replacementDelayMillis", "maxLearners"));
		String advertised = getAdvertisedEndpoint();
		return new MembershipController.Options(integer(membership, "refreshIntervalMillis", 1000, 1, Long.MAX_VALUE),
			integer(membership, "replacementDelayMillis", 30000, 1, Long.MAX_VALUE),
			(int) integer(membership, "maxLearners", 2, 1, 16),
			advertised == null ? null : NodeEndpoint.parse(advertised, advertisedPort));
	}

	/**
	 * Derives a stable preferred path from the group digest and configured node ID.
	 * The coordinator also checks for existing legacy state before opening this path.
	 * @return normalized absolute preferred directory; existing state is selected during coordinator startup
	 */
	public Path resolvedDataDirectory() {
		String explicit = getDataDirectory();
		if (explicit != null) return Path.of(explicit).toAbsolutePath().normalize();
		Path group = defaultGroupDirectory();
		return getNodeId() == null ? group : nodeDirectory(group, getNodeId());
	}

	Path defaultGroupDirectory() {
		return Path.of(".inqwise-leader", digest(Objects.requireNonNull(getGroupName(), "groupName")))
			.toAbsolutePath().normalize();
	}

	static Path nodeDirectory(Path group, String nodeId) {
		return group.resolve("nodes").resolve(digest(nodeId));
	}

	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 required", e); }
	}

	private void validate() {
		checkKeys(json, KEYS);
		if (!Set.of("http", "tcp").contains(getTransport())) throw new IllegalArgumentException("Unknown transport");
		new RaftIdentity(getNodeId() == null ? "validation" : getNodeId(), Objects.requireNonNull(getGroupName(), "groupName"));
		RaftIdentity.validateNodeId(Objects.requireNonNull(getBootstrapAuthorityId(), "bootstrapAuthorityId"));
		if (getTargetVoterCount() % 2 == 0) throw new IllegalArgumentException("targetVoterCount must be odd");
		var advertised = getMembershipOptions().advertisedEndpoint();
		if (advertised != null && getNodeId() != null && advertised.expectedNodeId() != null
			&& !getNodeId().equals(advertised.expectedNodeId())) throw new IllegalArgumentException("Advertised identity mismatch");
		getTiming(); getLeaveTimeoutMillis(); resolvedDataDirectory();
	}

	/** Mutable configuration builder. Each build creates an independent, validated immutable options instance. */
	public static final class Builder {
		private final JsonObject json;

		private Builder() {
			json = new JsonObject().put("targetVoterCount", RaftGenesis.DEFAULT_VOTER_COUNT).put("leaveTimeoutMillis", 30000L)
				.put("discovery", DiscoveryOptions.staticEndpoints().toJson());
		}

		private Builder(JsonObject json) {
			this();
			this.json.mergeIn(Objects.requireNonNull(json, "json").copy());
		}

		/**
		 * Selects the communication protocol. TCP is the default; HTTP requires explicit selection.
		 * @param value "http" or "tcp"; use the same value on every peer
		 * @return this builder
		 */
		public Builder transport(String value) { return put("transport", value); }
		/**
		 * Sets the required consensus group.
		 * @param value group name
		 * @return this builder
		 */
		public Builder groupName(String value) { return put("groupName", value); }
		/**
		 * Sets the authority shared by all participants; no separate local authority boolean is necessary.
		 * @param value designated identity
		 * @return this builder
		 */
		public Builder bootstrapAuthorityId(String value) { return put("bootstrapAuthorityId", value); }
		/**
		 * Sets an optional initial/expected identity, which must match existing storage and TLS credentials.
		 * @param value node ID; null restores recovery/generation behavior
		 * @return this builder
		 */
		public Builder nodeId(String value) { return optional("nodeId", value); }
		/**
		 * Overrides automatic persistent-directory selection for a custom volume or storage layout.
		 * @param value directory path; null selects the default
		 * @return this builder
		 */
		public Builder dataDirectory(String value) { return optional("dataDirectory", value); }
		/**
		 * Sets the odd initial target (1..63); this does not resize an initialized cluster.
		 * @param value initial voter target
		 * @return this builder
		 */
		public Builder targetVoterCount(int value) { return put("targetVoterCount", value); }
		/**
		 * Overrides the callback endpoint used with a wildcard listener or address translation.
		 * @param value IPv4 endpoint, or null to advertise the concrete listener
		 * @return this builder
		 */
		public Builder advertisedEndpoint(String value) { return optional("advertisedEndpoint", value); }
		/**
		 * Sets static endpoints or an SRV name with optional standard Vert.x DNS options.
		 * @param value immutable discovery configuration
		 * @return this builder
		 */
		public Builder discovery(DiscoveryOptions value) { return put("discovery", Objects.requireNonNull(value).toJson()); }
		/**
		 * Sets the default graceful membership deadline.
		 * @param value positive milliseconds
		 * @return this builder
		 */
		public Builder leaveTimeoutMillis(long value) { return put("leaveTimeoutMillis", value); }
		/**
		 * Sets advanced election timing through the existing validated core type.
		 * @param value election timing
		 * @return this builder
		 */
		public Builder timing(RaftElection.Timing value) {
			Objects.requireNonNull(value, "timing");
			return put("timing", new JsonObject().put("electionMinMillis", value.electionMinMillis())
				.put("electionMaxMillis", value.electionMaxMillis()).put("heartbeatMillis", value.heartbeatMillis()));
		}
		/**
		 * Sets optional controller bounds: refreshIntervalMillis, replacementDelayMillis and maxLearners.
		 * @param value membership settings, without an advertisedEndpoint (use the top-level option)
		 * @return this builder
		 */
		public Builder membership(JsonObject value) { return put("membership", Objects.requireNonNull(value).copy()); }

		/**
		 * Validates all fields and creates options without filesystem or network I/O.
		 * Unknown fields, explicit JSON nulls, missing required values and invalid bounds are rejected.
		 * @return independent immutable options
		 */
		public LeaderOptions build() { return new LeaderOptions(json); }

		private Builder put(String key, Object value) {
			json.put(key, Objects.requireNonNull(value, key)); return this;
		}
		private Builder optional(String key, String value) {
			if (value == null) json.remove(key); else json.put(key, value); return this;
		}
	}
}
