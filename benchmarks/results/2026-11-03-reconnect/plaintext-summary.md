# Local transport benchmark

Independent JVM forks; medians across forks. Percentiles are computed inside each fork, then reported as a median (not a pooled percentile).

| Scenario | Protocol | Success ops/s | p50 ms | p99 ms (min–max) | CPU µs/success | Alloc bytes/success | Failures/attempts |
|---|---|---:|---:|---:|---:|---:|---:|
| heartbeat_serial | http | 17,985 | 0.048 | 0.125 (0.124–0.128) | 139.6 | 23,461 | 0/60000 |
| heartbeat_serial | tcp | 25,899 | 0.033 | 0.090 (0.088–0.094) | 71.9 | 15,486 | 0/60000 |
| heartbeat_concurrent | http | 45,167 | 0.135 | 0.310 (0.277–0.310) | 75.1 | 23,550 | 0/60000 |
| heartbeat_concurrent | tcp | 94,394 | 0.078 | 0.142 (0.140–0.143) | 34.5 | 14,953 | 0/60000 |
| append_1k | http | 43,443 | 0.150 | 0.280 (0.276–0.288) | 78.2 | 39,416 | 0/60000 |
| append_1k | tcp | 85,954 | 0.084 | 0.164 (0.161–0.181) | 36.6 | 36,044 | 0/60000 |
| heartbeat_with_snapshots | http | 25,621 | 0.184 | 1.591 (1.586–1.681) | 137.1 | 142,136 | 0/60000 |
| heartbeat_with_snapshots | tcp | 60,117 | 0.097 | 0.389 (0.363–0.416) | 68.6 | 95,377 | 0/60000 |
| healthy_with_slow_peer | http | 58,397 | 0.112 | 0.233 (0.207–0.246) | 32.8 | 23,716 | 0/60000 |
| healthy_with_slow_peer | tcp | 116,493 | 0.064 | 0.125 (0.120–0.131) | 13.1 | 15,060 | 0/60000 |
| overloaded_slow_peer | http | 94 | 365.468 | 677.356 (675.070–681.240) | 974.1 | 54,051 | 1344/1536 |
| overloaded_slow_peer | tcp | 308 | 51.935 | 51.940 (51.894–51.981) | 4366.6 | 225,854 | 1488/1536 |
| heartbeat_after_overload | http | 57,320 | 0.113 | 0.213 (0.206–0.228) | 37.3 | 23,726 | 0/60000 |
| heartbeat_after_overload | tcp | 112,767 | 0.065 | 0.130 (0.122–0.133) | 12.6 | 15,062 | 0/60000 |
| receiver_restart_recovery | http | — | 1.278 | 1.633 (1.495–3.668) | — | — | 0 retries / 30 restarts |
| receiver_restart_recovery | tcp | — | 1.047 | 1.238 (1.136–1.454) | — | — | 0 retries / 30 restarts |

CPU and allocations include all benchmark peers, background traffic, and the harness in the same JVM. Overload percentiles describe successful requests only; inspect failures before comparing rates. Raw JSON also records sampled heap, GC, background progress, and environment metadata.
