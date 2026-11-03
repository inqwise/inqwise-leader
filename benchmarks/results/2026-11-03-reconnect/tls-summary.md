# Local transport benchmark

Independent JVM forks; medians across forks. Percentiles are computed inside each fork, then reported as a median (not a pooled percentile).

| Scenario | Protocol | Success ops/s | p50 ms | p99 ms (min–max) | CPU µs/success | Alloc bytes/success | Failures/attempts |
|---|---|---:|---:|---:|---:|---:|---:|
| heartbeat_serial | http | 15,226 | 0.057 | 0.145 (0.142–0.148) | 159.5 | 38,288 | 0/60000 |
| heartbeat_serial | tcp | 21,479 | 0.039 | 0.108 (0.105–0.108) | 91.2 | 21,760 | 0/60000 |
| heartbeat_concurrent | http | 31,700 | 0.196 | 0.457 (0.409–0.728) | 116.8 | 38,585 | 0/60000 |
| heartbeat_concurrent | tcp | 65,190 | 0.103 | 0.242 (0.230–0.246) | 58.4 | 20,365 | 0/60000 |
| append_1k | http | 37,586 | 0.171 | 0.309 (0.294–0.321) | 71.8 | 55,929 | 0/60000 |
| append_1k | tcp | 62,623 | 0.103 | 0.274 (0.246–0.282) | 41.2 | 43,212 | 0/60000 |
| heartbeat_with_snapshots | http | 14,548 | 0.305 | 2.498 (2.208–2.503) | 247.0 | 207,768 | 0/60000 |
| heartbeat_with_snapshots | tcp | 28,769 | 0.187 | 0.901 (0.880–0.901) | 140.8 | 153,719 | 0/60000 |
| healthy_with_slow_peer | http | 42,761 | 0.151 | 0.275 (0.262–0.300) | 54.7 | 38,813 | 0/60000 |
| healthy_with_slow_peer | tcp | 84,240 | 0.086 | 0.171 (0.169–0.181) | 31.9 | 20,739 | 0/60000 |
| overloaded_slow_peer | http | 94 | 367.379 | 678.087 (675.465–681.705) | 1702.8 | 76,232 | 1344/1536 |
| overloaded_slow_peer | tcp | 307 | 52.033 | 52.059 (51.938–52.090) | 4202.8 | 237,046 | 1488/1536 |
| heartbeat_after_overload | http | 40,025 | 0.155 | 0.276 (0.272–0.304) | 59.4 | 38,916 | 0/60000 |
| heartbeat_after_overload | tcp | 82,836 | 0.086 | 0.164 (0.161–0.166) | 21.3 | 20,736 | 0/60000 |
| receiver_restart_recovery | http | — | 17.038 | 18.723 (17.767–19.611) | — | — | 0 retries / 30 restarts |
| receiver_restart_recovery | tcp | — | 19.126 | 30.206 (28.985–31.860) | — | — | 0 retries / 30 restarts |

CPU and allocations include all benchmark peers, background traffic, and the harness in the same JVM. Overload percentiles describe successful requests only; inspect failures before comparing rates. Raw JSON also records sampled heap, GC, background progress, and environment metadata.
