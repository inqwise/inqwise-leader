# Transport benchmark

Run from the repository root with Maven, Python 3, and JDK 21 configured:

```sh
bash benchmarks/run-transport.sh target/transport-benchmark-tls
BENCHMARK_TLS=false bash benchmarks/run-transport.sh target/transport-benchmark-plaintext
```

Latest measurements and the reconnect fix are documented in [the 2026-11-03 follow-up](results/2026-11-03-reconnect/README.md).
The [2026-11-01 report](results/2026-11-01-loopback/README.md) preserves the original implementation measurements.

The output directory must not contain prior `http-*.json` or `tcp-*.json` reports. Each command builds the test
classpath, then starts three fresh JVMs per transport, alternating HTTP/TCP order between trials. No benchmark runs
inside Surefire or with its JaCoCo agent. Each JVM uses `-Xms256m -Xmx256m -XX:ActiveProcessorCount=4`.
The benchmark is a standalone test-source executable; it adds no runtime dependency or Maven profile.

Configure workload size with `BENCHMARK_TRIALS` (default 3), `BENCHMARK_SAMPLES` (10000),
`BENCHMARK_WARMUP` (2000), `BENCHMARK_CONCURRENCY` (8, maximum 16), and `BENCHMARK_TLS` (`true` by default).
A short harness check can use one trial, 1000 samples, and 200 warmup requests. Use a fresh output directory each time.
The TLS run uses the repository's public test credentials on loopback only; never deploy those credentials.

## What is measured

Three independent Vert.x instances in one JVM act as sender, healthy receiver, and slow receiver. Each has two event
loops and four workers. Actual TCP/HTTP sockets, binary codecs, identity checks, and TLS operate over IPv4 loopback.
The receiver accepts decoded messages without a Raft engine or disk writes. Latency is **transport acceptance round trip**,
not a durable Raft acknowledgement, commit, or end-to-end leadership latency. Empty AppendEntries messages represent
heartbeats. Successful requests alone contribute to latency percentiles; all failures and attempts remain visible.
The load is closed loop: each lane submits its next request after completion. It does not estimate latency under an
independent fixed arrival rate and can hide queueing caused by coordinated omission.

Scenarios:

- Serial and concurrent heartbeats, then concurrent appends carrying a 1 KiB entry.
- Heartbeats to a receiver also receiving continuous 256 KiB snapshots on one background lane.
- Heartbeats to a healthy receiver while eight lanes target another peer with a 50 ms asynchronous acceptance delay.
- 512 requests at concurrency 96 to that slow peer, intentionally exceeding transport limits; then a healthy-peer run
  to check that capacity has recovered. Overload has 64 warmup requests; normal scenarios use the configured warmup.
- Ten receiver restarts on the same port, after two warmup restarts. Recovery is measured from before listener shutdown
  to the first accepted heartbeat, including rebinding, TLS, and reconnect backoff. Failures before recovery are retries,
  reported separately from the ten successful recoveries. Raw samples separate listener close, listener bind, and
  dispatch recovery time; phase medians need not sum to the median total. Retries are paced at 25 ms with a ten-second deadline.

Each ordinary scenario records throughput, p50/p95/p99/max, failure reasons, process CPU time, allocated bytes, heap
before/after, heap sampled every 10 ms, and GC counts/time. CPU/allocation include the sender, both receivers, background
traffic, and the harness together. Heap values are sampled JVM heap, not RSS, retained live objects, or native socket/TLS
memory. A short run may finish between heap samples. JIT and GC are not disabled; no forced GC runs between scenarios.
JVM and OS metadata are saved in each JSON report. Missing MXBean metrics are represented as null.

`summary.md` reports medians of per-fork metrics and p99 ranges. Its p99 is a median of fork percentiles, not a pooled
percentile or confidence interval. `combined.json` retains all raw reports. Background counts, error rates, and GC must
be checked before interpreting rates. Large throughput during overload can reflect fast rejection rather than useful work.

These are local diagnostic comparisons. They do not establish production network latency, storage performance, sustained
capacity, or behavior when a peer stops reading its socket. Re-run on deployment hardware with realistic network shaping,
TLS credentials, and traffic before tuning bounds or election timing. Production defaults are not adjusted by this harness.

## Coordinated migration check

```sh
mvn -Dtest=RaftProcessTest#coordinatedHttpToTcpRestartPreservesSnapshotsTailAndMembership test
```

This test starts three authenticated HTTP JVMs, commits data, snapshots, commits a later log tail, and closes all nodes.
It then starts TCP JVMs on the same ports and directories. It checks node/cluster identity, genesis, voters, target,
term and commit monotonicity, restored snapshot/application data, and a new commit replicated to all TCP nodes.
It does not exercise a mixed-protocol rolling migration; peers must switch together.
