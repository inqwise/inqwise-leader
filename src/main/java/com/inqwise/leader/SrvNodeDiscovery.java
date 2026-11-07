package com.inqwise.leader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.dns.DnsClient;
import io.vertx.core.dns.SrvRecord;

/** Resolves all SRV targets to IPv4; it never selects voters or applies load balancing to Raft messages. */
public final class SrvNodeDiscovery implements NodeDiscovery {
	private final Context context;
	private final DnsClient dns;
	private final String name;

	/**
	 * Creates an SRV source using an externally owned Vert.x DNS client.
	 * @param context owning event-loop context
	 * @param dns DNS client, configured with bounded query timeout; caller closes it
	 * @param name service name such as _inqwise-leader._tcp.example.net
	 */
	public SrvNodeDiscovery(Context context, DnsClient dns, String name) {
		this.context = Objects.requireNonNull(context, "context");
		if (!context.isEventLoopContext()) throw new IllegalArgumentException("An event-loop context is required");
		this.dns = Objects.requireNonNull(dns, "dns");
		this.name = Objects.requireNonNull(name, "name");
		if (!name.matches("_[A-Za-z0-9-]+\\._tcp\\.[A-Za-z0-9.-]+\\.?")) {
			throw new IllegalArgumentException("A TCP SRV service name is required");
		}
	}

	@Override public Future<Result> resolve() {
		return ContextDispatch.call(context, () -> ContextDispatch.relay(context, dns.resolveSRV(name)).compose(records -> {
			if (records.size() > 64) return Future.failedFuture(new IOException("Too many SRV records"));
			if (records.isEmpty()) return Future.succeededFuture(new Result(List.of(), java.util.Map.of(), 1000));
			var ordered = records.stream().sorted(Comparator.comparingInt(SrvRecord::priority)
				.thenComparing(SrvRecord::target, Comparator.nullsFirst(Comparator.naturalOrder()))
				.thenComparingInt(SrvRecord::port)).toList();
			var lookups = new ArrayList<Future<List<NodeEndpoint>>>();
			for (var record : ordered) {
				if (record.target() == null || record.target().equals(".") || record.target().isEmpty() || record.port() < 1 || record.port() > 65535) {
					lookups.add(Future.failedFuture(new IOException("Service unavailable or invalid SRV port")));
				} else {
					lookups.add(ContextDispatch.relay(context, dns.resolveA(record.target())).map(addresses -> {
						if (addresses.isEmpty()) throw new IllegalArgumentException("SRV target has no IPv4 address");
						if (addresses.size() > 64) throw new IllegalArgumentException("Too many IPv4 addresses");
						return addresses.stream().map(ip -> new NodeEndpoint(ip, record.port(), null)).toList();
					}));
				}
			}
			return Future.join(lookups).transform(ignored -> {
				var endpoints = new LinkedHashSet<NodeEndpoint>();
				var failures = new HashMap<String, String>();
				long ttl = Long.MAX_VALUE;
				for (int i = 0; i < ordered.size(); i++) {
					var record = ordered.get(i);
					ttl = Math.min(ttl, record.ttl());
					if (lookups.get(i).succeeded()) endpoints.addAll(lookups.get(i).result());
					else failures.put((record.target() == null ? "." : record.target()) + ":" + record.port(), String.valueOf(lookups.get(i).cause().getMessage()));
				}
				if (endpoints.size() > 64) return Future.failedFuture(new IOException("Too many resolved endpoints"));
				// A-query TTLs are not exposed by DnsClient. Do not retain routes solely on this hint.
				long refresh = Math.max(1000, Math.min(ttl, 86400) * 1000);
				return Future.succeededFuture(new Result(List.copyOf(endpoints), failures, refresh));
			});
		}));
	}

}
