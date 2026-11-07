package com.inqwise.leader;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServerOptions;

/**
 * Opt-in loopback load harness, intentionally excluded from Surefire's *Test naming convention.
 * Measures authenticated transport acceptance, never durable Raft commit latency. See benchmarks/README.md.
 */
public final class TransportBenchmark implements AutoCloseable {
	private static final String GROUP = "benchmark";
	private static final String CLUSTER = "benchmark-cluster";
	private final String protocol;
	private final boolean tls;
	private final int samples;
	private final int warmup;
	private final int concurrency;
	private final List<Peer> peers = new ArrayList<>();
	private final JsonArray results = new JsonArray();
	private final AtomicLong messageIds = new AtomicLong();
	private final RaftSnapshot snapshot = new RaftSnapshot(100, 1,
		RaftConfiguration.stable(Set.of("node-a", "node-b", "node-c"), Set.of()), 0, "s".repeat(256 * 1024));
	private final List<RaftLogEntry> entries = List.of(new RaftLogEntry(1, 1, RaftLogEntry.Kind.DATA, "a".repeat(1024)));
	private final OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
	private final ThreadMXBean threads = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
	private Peer sender;
	private Peer healthy;
	private Peer slow;

	private TransportBenchmark(String protocol, boolean tls, int samples, int warmup, int concurrency) {
		if (!Set.of("http", "tcp").contains(protocol) || samples < 100 || warmup < 100
			|| concurrency < 1 || concurrency > 16) throw new IllegalArgumentException("Invalid benchmark arguments");
		this.protocol = protocol; this.tls = tls; this.samples = samples;
		this.warmup = warmup; this.concurrency = concurrency;
		if (threads != null && threads.isThreadAllocatedMemorySupported()) threads.setThreadAllocatedMemoryEnabled(true);
	}

	private final class Peer {
		final String id;
		final Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2).setWorkerPoolSize(4)
			.setInternalBlockingPoolSize(2));
		final Context context = vertx.getOrCreateContext();
		final AtomicLong delayMillis = new AtomicLong();
		AbstractRaftClusterTransport transport;
		int port;

		Peer(String id) { this.id = id; peers.add(this); }
		void listen(int requestedPort) throws Exception {
			var identity = new RaftIdentity(id, GROUP);
			if (protocol.equals("tcp")) {
				var server = tls ? TcpRaftTransportTest.serverOptions(id) : new NetServerOptions();
				var client = tls ? TcpRaftTransportTest.clientOptions(id) : new NetClientOptions();
				transport = new TcpRaftTransport(context, identity, CLUSTER,
					server.setHost("127.0.0.1").setPort(requestedPort), client);
			} else {
				var server = tls ? HttpRaftTransportTest.serverOptions(id) : new HttpServerOptions();
				var client = tls ? HttpRaftTransportTest.clientOptions(id) : new HttpClientOptions();
				transport = new HttpRaftTransport(context, identity, CLUSTER,
					server.setHost("127.0.0.1").setPort(requestedPort), client);
			}
			port = await(transport.listen(envelope -> {
				long delay = delayMillis.get();
				if (delay == 0) return Future.succeededFuture();
				Promise<Void> accepted = ContextDispatch.promise(context);
				vertx.setTimer(delay, ignored -> accepted.tryComplete()); return accepted.future();
			}));
		}
		NodeEndpoint endpoint() { return new NodeEndpoint("127.0.0.1", port, id); }
	}

	private void setup() throws Exception {
		sender = new Peer("node-a"); healthy = new Peer("node-b"); slow = new Peer("node-c");
		for (var peer : peers) peer.listen(0);
		var discovered = await(sender.transport.discover(List.of(healthy.endpoint(), slow.endpoint())));
		if (!discovered.failures().isEmpty()) throw new IllegalStateException(discovered.failures().toString());
	}

	private RaftElection.Envelope envelope(Peer target, String kind) {
		long id = messageIds.incrementAndGet();
		RaftElection.Message message = kind.equals("snapshot") ? new RaftElection.InstallSnapshot(1, id, snapshot)
			: new RaftElection.AppendEntries(1, id, new RaftElection.LogPosition(0, 0),
				kind.equals("append") ? entries : List.of(), 0);
		return new RaftElection.Envelope(GROUP, CLUSTER, sender.id, target.id, message);
	}

	private final class Load {
		final int count;
		final int width;
		final IntFunction<Future<Void>> operation;
		final Promise<Load> done = ContextDispatch.promise(sender.context);
		final long[] latencies;
		final Map<String, Integer> errors = new HashMap<>();
		int issued;
		int completed;
		int succeeded;
		long started;
		long ended;
		Load(int count, int width, IntFunction<Future<Void>> operation) {
			this.count = count; this.width = width; this.operation = operation; latencies = new long[count];
		}
		Future<Load> start() {
			started = System.nanoTime();
			for (int i = 0; i < Math.min(count, width); i++) next();
			return done.future();
		}
		void next() {
			if (issued == count) return;
			int index = issued++; long start = System.nanoTime();
			operation.apply(index).onComplete(result -> {
				if (result.succeeded()) latencies[succeeded++] = System.nanoTime() - start;
				else errors.merge(result.cause().getClass().getSimpleName() + ": " + result.cause().getMessage(), 1, Integer::sum);
				if (++completed == count) { ended = System.nanoTime(); done.tryComplete(this); }
				else sender.context.runOnContext(ignored -> next());
			});
		}
		JsonObject stats(String name) {
			long[] sorted = Arrays.copyOf(latencies, succeeded); Arrays.sort(sorted);
			double seconds = (ended - started) / 1e9;
			return new JsonObject().put("scenario", name).put("attempts", count).put("concurrency", width)
				.put("successes", succeeded).put("failures", count - succeeded).put("errors", new JsonObject(new HashMap<String, Object>(errors)))
				.put("seconds", seconds).put("successfulOpsPerSecond", succeeded / seconds)
				.put("p50Millis", percentile(sorted, 0.50)).put("p95Millis", percentile(sorted, 0.95))
				.put("p99Millis", percentile(sorted, 0.99)).put("maxMillis", percentile(sorted, 1));
		}
	}

	private final class Background {
		final Peer target;
		final String kind;
		final int width;
		final AtomicBoolean stopped = new AtomicBoolean();
		final AtomicInteger successes = new AtomicInteger();
		final AtomicInteger failures = new AtomicInteger();
		final Promise<Void> first = ContextDispatch.promise(sender.context);
		final Promise<Void> drained = ContextDispatch.promise(sender.context);
		int active;
		Background(Peer target, String kind, int width) { this.target = target; this.kind = kind; this.width = width; }
		Future<Void> start() {
			active = width;
			for (int i = 0; i < width; i++) next();
			return first.future();
		}
		void next() {
			if (stopped.get()) { if (--active == 0) drained.tryComplete(); return; }
			sender.transport.send(envelope(target, kind)).onComplete(result -> {
				if (result.succeeded()) { successes.incrementAndGet(); first.tryComplete(); }
				else { failures.incrementAndGet(); first.tryFail(result.cause()); }
				sender.context.runOnContext(ignored -> next());
			});
		}
		Future<Void> stop() { stopped.set(true); return drained.future(); }
	}

	private record Resources(long cpuNanos, long allocations, long heapBytes, long gcCount, long gcMillis) {}
	private Resources resources() {
		long count = 0, millis = 0;
		for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			count += Math.max(0, gc.getCollectionCount()); millis += Math.max(0, gc.getCollectionTime());
		}
		return new Resources(os == null ? -1 : os.getProcessCpuTime(),
			threads == null || !threads.isThreadAllocatedMemoryEnabled() ? -1 : threads.getTotalThreadAllocatedBytes(),
			ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), count, millis);
	}
	private final class Measurement {
		final Resources before = resources();
		final AtomicLong peak = new AtomicLong(before.heapBytes());
		final long timer = sender.vertx.setPeriodic(10, ignored -> peak.accumulateAndGet(
			ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max));
		JsonObject finish(JsonObject stats) {
			sender.vertx.cancelTimer(timer); var after = resources();
			return stats.put("processCpuMillis", delta(before.cpuNanos(), after.cpuNanos(), 1e6))
				.put("allocatedBytes", delta(before.allocations(), after.allocations(), 1))
				.put("heapBeforeBytes", before.heapBytes()).put("heapAfterBytes", after.heapBytes())
				.put("peakSampledHeapBytes", Math.max(peak.get(), after.heapBytes()))
				.put("gcCollections", after.gcCount() - before.gcCount()).put("gcMillis", after.gcMillis() - before.gcMillis());
		}
	}

	private Load load(int count, int width, Peer target, String kind) throws Exception {
		var load = new Load(count, width, ignored -> sender.transport.send(envelope(target, kind)));
		return await(ContextDispatch.call(sender.context, load::start));
	}
	private void measure(String name, int count, int width, Peer target, String kind) throws Exception {
		load(name.equals("overloaded_slow_peer") ? 64 : warmup, Math.min(width, concurrency), target, kind);
		var measurement = new Measurement(); var load = load(count, width, target, kind);
		results.add(measurement.finish(load.stats(name)).put("encodedPayloadBytes", RaftWireCodec.encode(envelope(target, kind)).length()));
	}
	private void withBackground(String name, Peer backgroundTarget, String kind, int width) throws Exception {
		load(warmup, concurrency, healthy, "heartbeat");
		var background = new Background(backgroundTarget, kind, width);
		try {
			await(ContextDispatch.call(sender.context, background::start));
			int before = background.successes.get(), failedBefore = background.failures.get();
			var measurement = new Measurement(); var load = load(samples, concurrency, healthy, "heartbeat");
			var stats = measurement.finish(load.stats(name)).put("backgroundSuccesses", background.successes.get() - before)
				.put("backgroundFailures", background.failures.get() - failedBefore);
			results.add(stats);
		} finally { await(ContextDispatch.call(sender.context, background::stop)); }
	}

	private void reconnect() throws Exception {
		// Warm TLS/provider classes before measuring restart recovery.
		for (int i = 0; i < 2; i++) restart();
		var measurement = new Measurement(); long[] times = new long[10]; int attempts = 0;
		long[] closeTimes = new long[10], bindTimes = new long[10], dispatchTimes = new long[10];
		var restartSamples = new JsonArray();
		for (int i = 0; i < times.length; i++) {
			var result = restart(); times[i] = result[0]; attempts += (int) result[1];
			closeTimes[i] = result[2]; bindTimes[i] = result[3]; dispatchTimes[i] = result[4];
			restartSamples.add(new JsonObject().put("totalMillis", result[0] / 1e6).put("attempts", result[1])
				.put("listenerCloseMillis", result[2] / 1e6).put("listenerBindMillis", result[3] / 1e6)
				.put("dispatchRecoveryMillis", result[4] / 1e6));
		}
		Arrays.sort(times); Arrays.sort(closeTimes); Arrays.sort(bindTimes); Arrays.sort(dispatchTimes);
		results.add(measurement.finish(new JsonObject().put("scenario", "receiver_restart_recovery")
			.put("restarts", times.length).put("attempts", attempts).put("failuresBeforeRecovery", attempts - times.length)
			.put("listenerCloseP50Millis", percentile(closeTimes, 0.5))
			.put("listenerBindP50Millis", percentile(bindTimes, 0.5))
			.put("dispatchRecoveryP50Millis", percentile(dispatchTimes, 0.5)).put("retryIntervalMillis", 25)
			.put("restartSamples", restartSamples)
			.put("p50Millis", percentile(times, 0.5)).put("p95Millis", percentile(times, 0.95))
			.put("p99Millis", percentile(times, 0.99)).put("maxMillis", percentile(times, 1))));
	}
	private long[] restart() throws Exception {
		long start = System.nanoTime(); await(healthy.transport.close()); long closedAt = System.nanoTime();
		healthy.listen(healthy.port); long boundAt = System.nanoTime();
		Promise<long[]> recovered = ContextDispatch.promise(sender.context);
		long deadline = sender.vertx.setTimer(10000, ignored -> recovered.tryFail("Receiver did not recover"));
		class Retry {
			int attempts;
			void send() {
				if (recovered.future().isComplete()) return;
				attempts++;
				sender.transport.send(envelope(healthy, "heartbeat")).onComplete(result -> {
					if (result.succeeded()) {
						long end = System.nanoTime();
						recovered.tryComplete(new long[] {end - start, attempts, closedAt - start, boundAt - closedAt, end - boundAt});
					} else sender.vertx.setTimer(25, ignored -> send());
				});
			}
		}
		try {
			return await(ContextDispatch.call(sender.context, () -> { new Retry().send(); return recovered.future(); }));
		} finally { sender.vertx.cancelTimer(deadline); }
	}

	private JsonObject run(int trial) throws Exception {
		setup();
		measure("heartbeat_serial", samples, 1, healthy, "heartbeat");
		measure("heartbeat_concurrent", samples, concurrency, healthy, "heartbeat");
		measure("append_1k", samples, concurrency, healthy, "append");
		withBackground("heartbeat_with_snapshots", healthy, "snapshot", 1);
		slow.delayMillis.set(50);
		withBackground("healthy_with_slow_peer", slow, "heartbeat", 8);
		measure("overloaded_slow_peer", 512, 96, slow, "heartbeat");
		slow.delayMillis.set(0);
		measure("heartbeat_after_overload", samples, concurrency, healthy, "heartbeat");
		reconnect();
		return new JsonObject().put("protocol", protocol).put("tls", tls).put("trial", trial)
			.put("timestamp", Instant.now().toString()).put("javaVersion", System.getProperty("java.version"))
			.put("vm", System.getProperty("java.vm.name")).put("os", System.getProperty("os.name"))
			.put("osVersion", System.getProperty("os.version")).put("arch", System.getProperty("os.arch"))
			.put("availableProcessors", Runtime.getRuntime().availableProcessors())
			.put("jvmArguments", new JsonArray(ManagementFactory.getRuntimeMXBean().getInputArguments()))
			.put("samples", samples).put("warmup", warmup).put("concurrency", concurrency)
			.put("measurement", "loopback transport acceptance; no storage or Raft engine")
			.put("snapshotDataBytes", snapshot.data().length()).put("slowPeerAcceptanceDelayMillis", 50)
			.put("scenarios", results);
	}

	@Override public void close() throws Exception {
		Exception failure = null;
		for (var peer : peers) {
			try { if (peer.transport != null) await(peer.transport.close()); }
			catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
			try { await(peer.vertx.close()); }
			catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
		}
		if (failure != null) throw failure;
	}
	private static Double delta(long before, long after, double divisor) {
		return before < 0 || after < 0 ? null : (after - before) / divisor;
	}
	private static Double percentile(long[] sorted, double fraction) {
		return sorted.length == 0 ? null : sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)] / 1e6;
	}
	private static <T> T await(Future<T> future) throws Exception {
		return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
	}
	public static void main(String[] args) throws Exception {
		if (args.length != 7) throw new IllegalArgumentException("protocol tls output samples warmup concurrency trial");
		JsonObject report;
		try (var benchmark = new TransportBenchmark(args[0], Boolean.parseBoolean(args[1]),
			Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]))) {
			report = benchmark.run(Integer.parseInt(args[6]));
		}
		Files.writeString(Path.of(args[2]), report.encodePrettily() + "\n");
		System.out.println("Wrote " + args[2]);
	}
}
