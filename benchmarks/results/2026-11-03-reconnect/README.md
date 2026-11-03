# TCP reconnect investigation — 2026-11-03

## Cause and fix

The original TCP implementation called `Dial.failed()` whenever a session closed, including a previously established,
verified connection. That imposed a 50–75 ms initial backoff before the next operation could even attempt to reconnect.
The benchmark's unchanged 25 ms retry interval rounded that delay upward. The HTTP transport did not impose this delay.
The original restart numbers therefore included an avoidable policy penalty, rather than measuring only socket/RPC cost.

`Session.fail` now allows the next operation to reconnect immediately after an established connection closes. Failed
connection and identity-handshake attempts still use exponential backoff. Requests already in flight still fail; the
transport never silently replays them. No queue limits, message formats, TLS requirements, or consensus rules changed.

The regression test for disconnect/reconnect failed with the original code and passes with the fix, without its former
120 ms sleep. A separate test confirms that a failed identity handshake still prevents an immediate second connection.

Final validation: Java 21 `mvn clean verify` passed with 436 tests, zero failures/errors, and successful packaging.

## Measurements

The follow-up uses the same workload sizes and fork order as [the historical run](../2026-11-01-loopback/README.md):
three fresh JVMs per transport per mode, 20,000 measured requests and 5,000 warmups per ordinary scenario,
concurrency eight, OpenJDK 21.0.13.0.3, macOS 27.0.1/aarch64, 256 MiB heap, and four JVM-visible processors.
The complete restart timing still includes listener teardown/recreation, TLS, and the first accepted heartbeat.
The historical and follow-up runs occurred on different days; the follow-up reruns HTTP as a current control.
All normal foreground/background workloads completed without errors; all 120 receiver restarts recovered on their first attempt.

| Mode | Historical TCP recovery p50 | Fixed TCP recovery p50 | Current HTTP recovery p50 | Fixed TCP recovery p99 | TCP retry failures / 30 restarts |
|---|---:|---:|---:|---:|---:|
| tls | 75.666 ms | 19.126 ms | 17.038 ms | 30.206 ms | 0 |
| plaintext | 79.653 ms | 1.047 ms | 1.278 ms | 1.238 ms | 0 |

Values are medians of per-fork percentiles, not pooled percentiles or confidence intervals. The new raw samples split
restart recovery into three phases:

| Restart phase (TLS) | HTTP median | TCP median |
|---|---:|---:|
| Listener shutdown | 0.307 ms | 0.353 ms |
| Transport construction and listener bind | 9.648 ms | 9.561 ms |
| New connection, TLS, peer handshake and dispatch | 7.067 ms | 9.110 ms |

Phase medians need not sum to the median total. Listener recreation alone costs around 9.6 ms with TLS for both
implementations. This is not a pure network round-trip or a hot-connection benchmark. Removing HTTP cannot eliminate
shared listener/TLS setup. The TCP implementation also performs its own peer-identity exchange before RPC dispatch.
Plaintext avoids the TLS setup and now recovers in about 1 ms for both transports.

Steady-state concurrent TLS heartbeat dispatch remains faster for TCP: 65,190 versus 31,700 successful operations/s,
with median fork p99 of 0.242 versus 0.457 ms. Both transports use pooled/persistent TCP sockets, the same binary payload
codec, and the Vert.x runtime; the HTTP baseline is not opening a new connection or encoding JSON for each message.
A framing change therefore need not produce an order-of-magnitude reduction in end-to-end latency.

See [TLS summary](tls-summary.md), [plaintext summary](plaintext-summary.md), [TLS raw samples](tls.json),
[plaintext raw samples](plaintext.json), and [source fingerprint/reproduction commands](manifest.json).
[The harness documentation](../../README.md) describes resource accounting, closed-loop load, and loopback limitations.

TCP remains the default. Failed-attempt backoff is retained for real outages. These measurements support removing the
unnecessary initial recovery delay; they do not establish production network or durable Raft commit latency.
