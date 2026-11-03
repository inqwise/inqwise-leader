# Local transport benchmark

Independent JVM forks; medians across forks. Percentiles are computed inside each fork, then reported as a median (not a pooled percentile).

| Scenario | Protocol | Success ops/s | p50 ms | p99 ms (min–max) | CPU µs/success | Alloc bytes/success | Failures/attempts |
|---|---|---:|---:|---:|---:|---:|---:|
| heartbeat_serial | http | 14,106 | 0.061 | 0.170 (0.160–0.174) | 162.2 | 38,233 | 0/60000 |
| heartbeat_serial | tcp | 18,259 | 0.045 | 0.128 (0.119–0.166) | 95.8 | 22,423 | 0/60000 |
| heartbeat_concurrent | http | 28,488 | 0.219 | 0.459 (0.452–0.779) | 125.4 | 38,561 | 0/60000 |
| heartbeat_concurrent | tcp | 62,406 | 0.108 | 0.254 (0.249–0.267) | 60.7 | 21,002 | 0/60000 |
| append_1k | http | 35,362 | 0.183 | 0.319 (0.309–0.319) | 70.1 | 55,928 | 0/60000 |
| append_1k | tcp | 61,463 | 0.112 | 0.259 (0.214–0.259) | 41.3 | 43,913 | 0/60000 |
| heartbeat_with_snapshots | http | 14,990 | 0.331 | 2.307 (1.857–2.310) | 248.1 | 203,871 | 0/60000 |
| heartbeat_with_snapshots | tcp | 26,999 | 0.207 | 0.914 (0.891–1.158) | 149.6 | 158,467 | 0/60000 |
| healthy_with_slow_peer | http | 37,560 | 0.173 | 0.326 (0.317–0.336) | 61.8 | 38,754 | 0/60000 |
| healthy_with_slow_peer | tcp | 77,229 | 0.093 | 0.186 (0.182–0.204) | 35.4 | 21,422 | 0/60000 |
| overloaded_slow_peer | http | 95 | 366.875 | 673.339 (664.122–674.607) | 2420.8 | 83,350 | 1344/1536 |
| overloaded_slow_peer | tcp | 300 | 53.110 | 53.239 (51.717–55.693) | 3189.4 | 236,460 | 1488/1536 |
| heartbeat_after_overload | http | 36,355 | 0.174 | 0.328 (0.292–0.523) | 62.6 | 38,751 | 0/60000 |
| heartbeat_after_overload | tcp | 76,978 | 0.096 | 0.182 (0.175–0.237) | 22.7 | 21,335 | 0/60000 |
| receiver_restart_recovery | http | — | 17.722 | 22.752 (18.007–24.480) | — | — | 0 retries / 30 restarts |
| receiver_restart_recovery | tcp | — | 75.666 | 102.257 (100.767–102.694) | — | — | 75 retries / 30 restarts |

CPU and allocations include all benchmark peers, background traffic, and the harness in the same JVM. Overload percentiles describe successful requests only; inspect failures before comparing rates. Raw JSON also records sampled heap, GC, background progress, and environment metadata.
