package com.inqwise.leader;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.vertx.core.Future;

/** Endpoint resolution only; persisted peer identities are obtained through the transport handshake. */
@FunctionalInterface
public interface NodeDiscovery {
	/**
	 * Resolves one endpoint generation without modifying membership or installed transport routes.
	 * @return endpoints, target failures, and the SRV refresh hint
	 */
	Future<Result> resolve();

	/**
	 * Provides immutable static endpoints without DNS lookup.
	 * @param endpoints parsed IPv4 endpoints
	 * @return static discovery source
	 */
	static NodeDiscovery staticEndpoints(List<NodeEndpoint> endpoints) {
		var copy = List.copyOf(endpoints).stream().distinct().toList();
		if (copy.size() > 64) throw new IllegalArgumentException("At most 64 endpoints are supported");
		return () -> Future.succeededFuture(new Result(copy, Map.of(), Long.MAX_VALUE));
	}

	/**
	 * One resolution pass. A DNS failure never implies a voting membership change.
	 * @param endpoints IPv4 endpoints without inferred identity
	 * @param failures failed target names
	 * @param refreshAfterMillis SRV TTL hint; static sources use Long.MAX_VALUE
	 */
	record Result(List<NodeEndpoint> endpoints, Map<String, String> failures, long refreshAfterMillis) {
		/**
		 * Copies bounded discovery data.
		 * @param endpoints resolved endpoints
		 * @param failures per-target errors
		 * @param refreshAfterMillis positive refresh hint
		 */
		public Result {
			endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
			failures = Map.copyOf(Objects.requireNonNull(failures, "failures"));
			if (endpoints.size() > 64 || refreshAfterMillis <= 0) throw new IllegalArgumentException("Invalid discovery bounds");
		}
	}
}
