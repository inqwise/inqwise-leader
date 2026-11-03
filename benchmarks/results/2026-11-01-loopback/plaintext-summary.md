# Local transport benchmark

Independent JVM forks; medians across forks. Percentiles are computed inside each fork, then reported as a median (not a pooled percentile).

| Scenario | Protocol | Success ops/s | p50 ms | p99 ms (min–max) | CPU µs/success | Alloc bytes/success | Failures/attempts |
|---|---|---:|---:|---:|---:|---:|---:|
| heartbeat_serial | http | 15,485 | 0.054 | 0.136 (0.133–0.180) | 148.8 | 23,454 | 0/60000 |
| heartbeat_serial | tcp | 23,206 | 0.036 | 0.098 (0.098–0.117) | 79.9 | 15,500 | 0/60000 |
| heartbeat_concurrent | http | 42,438 | 0.146 | 0.308 (0.277–0.324) | 74.0 | 23,558 | 0/60000 |
| heartbeat_concurrent | tcp | 87,249 | 0.084 | 0.155 (0.145–0.157) | 36.9 | 14,970 | 0/60000 |
| append_1k | http | 38,933 | 0.167 | 0.327 (0.318–0.372) | 84.2 | 39,406 | 0/60000 |
| append_1k | tcp | 78,467 | 0.090 | 0.187 (0.170–0.191) | 42.2 | 36,086 | 0/60000 |
| heartbeat_with_snapshots | http | 23,109 | 0.210 | 1.713 (1.680–1.730) | 145.7 | 150,334 | 0/60000 |
| heartbeat_with_snapshots | tcp | 58,032 | 0.103 | 0.366 (0.365–0.386) | 71.7 | 99,550 | 0/60000 |
| healthy_with_slow_peer | http | 47,098 | 0.137 | 0.280 (0.236–0.357) | 43.6 | 23,740 | 0/60000 |
| healthy_with_slow_peer | tcp | 102,922 | 0.073 | 0.138 (0.133–0.144) | 14.2 | 15,083 | 0/60000 |
| overloaded_slow_peer | http | 94 | 368.399 | 681.366 (678.140–681.591) | 1022.8 | 54,082 | 1344/1536 |
| overloaded_slow_peer | tcp | 307 | 52.015 | 52.056 (52.047–52.509) | 3290.6 | 225,966 | 1488/1536 |
| heartbeat_after_overload | http | 49,464 | 0.132 | 0.236 (0.226–0.262) | 42.3 | 23,700 | 0/60000 |
| heartbeat_after_overload | tcp | 101,470 | 0.074 | 0.135 (0.125–0.140) | 12.4 | 15,049 | 0/60000 |
| receiver_restart_recovery | http | — | 1.255 | 1.815 (1.566–2.047) | — | — | 0 retries / 30 restarts |
| receiver_restart_recovery | tcp | — | 79.653 | 84.907 (81.904–87.884) | — | — | 89 retries / 30 restarts |

CPU and allocations include all benchmark peers, background traffic, and the harness in the same JVM. Overload percentiles describe successful requests only; inspect failures before comparing rates. Raw JSON also records sampled heap, GC, background progress, and environment metadata.
