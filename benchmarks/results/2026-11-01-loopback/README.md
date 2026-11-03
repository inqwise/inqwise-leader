# Local TCP versus HTTP results — 2026-11-01

**Historical results:** the [2026-11-03 follow-up](../2026-11-03-reconnect/README.md) fixes the unnecessary initial reconnect backoff.
The raw reports below describe the original policy.

Twelve fresh JVM forks completed: three per protocol with mutual TLS, followed by three per protocol with plaintext.
Each normal scenario used 5,000 warmup requests and 20,000 measured requests. Concurrency was eight except for serial
heartbeats and intentional overload. Both peers ran on IPv4 loopback in the same JVM, with a third instance for the slow
peer. Environment: macOS 27.0.1/aarch64, OpenJDK 21.0.13.0.3, 256 MiB initial/maximum heap, and
`-XX:ActiveProcessorCount=4` (JVM sizing, not CPU affinity). The runner alternated protocol order between trials.

See [methodology and reproduction](../../README.md), [manifest/source fingerprint](manifest.json),
[TLS measurements](tls-summary.md), [plaintext measurements](plaintext-summary.md),
and the complete [TLS](tls.json) and [plaintext](plaintext.json) raw reports.

## Findings

All normal foreground and background workloads completed without transport errors. Every receiver restart recovered
within the ten-second harness deadline: 30 per protocol per TLS mode, 120 total. Intentional overload produced the
expected capacity rejections. These are transport acceptance measurements; they exclude durable storage, Raft processing,
commit acknowledgements, and realistic network delays.

| Workload (TLS) | HTTP | TCP |
|---|---:|---:|
| Concurrent heartbeat success ops/s, median | 28,488 | 62,406 |
| Concurrent heartbeat p99, median of fork p99s | 0.459 ms | 0.254 ms |
| Concurrent heartbeat process CPU per success | 125.4 µs | 60.7 µs |
| Concurrent heartbeat allocated bytes per success | 38,561 | 21,002 |
| Heartbeat p99 during snapshot traffic | 2.307 ms | 0.914 ms |
| Heartbeat p99 alongside a slow peer | 0.326 ms | 0.186 ms |
| Receiver restart recovery p50 | 17.722 ms | 75.666 ms |
| Receiver restart recovery p99 | 22.752 ms | 102.257 ms |

For concurrent TLS heartbeats, TCP delivered about 2.19 times the successful throughput, with about 46% fewer allocated
bytes per success. Plaintext showed the same direction: 87,249 versus 42,438 ops/s, with median fork p99 of 0.155 versus
0.308 ms. The raw reports include individual fork ranges; three forks are diagnostic evidence, not a confidence interval.
Later scenarios also benefit from longer JVM warmup, so compare protocols within a scenario rather than comparing one
scenario's absolute rate with another's.

TCP restart recovery includes its reconnect backoff: the TLS runs needed 75 retries across 30 restarts; HTTP needed none.
The faster steady-state transport therefore has a measurable recovery tradeoff. No request was automatically replayed by
the transport; the recovery harness explicitly retried at 25 ms intervals.

At concurrency 96 with a 50 ms acceptance delay, each 512-request burst accepted 64 HTTP requests and 16 TCP requests.
The remainder were rejected. TCP's per-connection cap limits that burst earlier than HTTP's global exchange cap.
The lower latency of successful TCP requests in this scenario does not mean it accepted more useful work; neither the
success-only latency nor the short burst's ops/s establishes sustainable capacity. Healthy-peer runs after overload had
zero errors for both transports.

## Resource observations

| Workload (TLS) | Protocol | Median sampled peak heap, MiB | GC collections across 3 forks | Median background completions/s |
|---|---|---:|---:|---:|
| heartbeat_concurrent | http | 160.2 | 15 | — |
| heartbeat_concurrent | tcp | 152.0 | 9 | — |
| heartbeat_with_snapshots | http | 251.9 | 606 | 535.1 |
| heartbeat_with_snapshots | tcp | 215.0 | 53 | 625.0 |
| healthy_with_slow_peer | http | 204.9 | 25 | 101.4 |
| healthy_with_slow_peer | tcp | 164.2 | 8 | 178.6 |

Heap was sampled every 10 ms; it is neither RSS nor retained live memory. GC counts are summed over the three measured
windows, not medians. Snapshot traffic puts substantial allocation/GC pressure on this 256 MiB heap, especially for HTTP.
There is no retained-heap or native-memory leak diagnosis in these measurements. CPU/allocations include all peers,
background traffic, and the harness. Background traffic is closed loop at each transport's own achieved rate, not a fixed
identical arrival rate. Inspect background completions before comparing the mixed-workload resource totals.

## Migration and decision

Final validation: Java 21 `mvn clean verify` passed with 435 tests, zero failures/errors, and successful packaging.

The new `RaftProcessTest.coordinatedHttpToTcpRestartPreservesSnapshotsTailAndMembership` test passed with three real,
mutually authenticated JVMs. It forms an HTTP cluster, commits snapshot data plus a later log tail, closes every node, and
restarts TCP on the same directories and ports. It verifies retained identity, genesis, membership, term/commit progress,
application data, and a fresh commit replicated to all TCP nodes. It does not validate a mixed-protocol rolling migration.

Keep TCP as the default and retain the current queue limits, five-second deadlines, and reconnect backoff. No production
tuning was made from loopback results. Before deployment, repeat on representative hosts/networks with fixed arrival-rate
traffic, network delay/loss, constrained bandwidth and socket readers, and full durable Raft operations. The present results
support the transport choice locally; they do not establish a production latency or capacity guarantee.
