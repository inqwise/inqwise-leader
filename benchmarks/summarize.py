#!/usr/bin/env python3
"""Summarize independent benchmark forks; keep errors visible and avoid averaging percentiles."""
import json
import statistics
import sys
from pathlib import Path

root = Path(sys.argv[1])
runs = [json.loads(path.read_text()) for path in sorted(root.glob("*-*.json"))
        if path.name.startswith(("http-", "tcp-"))]
if not runs:
    raise SystemExit("No benchmark reports found")
profile_keys = ("tls", "samples", "warmup", "concurrency", "javaVersion", "os", "osVersion", "arch", "availableProcessors")
if len({tuple(r[k] for k in profile_keys) for r in runs}) != 1:
    raise SystemExit("Cannot combine different workload configurations or environments")
if {r["trial"] for r in runs if r["protocol"] == "http"} != {r["trial"] for r in runs if r["protocol"] == "tcp"}:
    raise SystemExit("HTTP and TCP must have matching trial sets")


def median(values):
    values = [value for value in values if value is not None]
    return statistics.median(values) if values else None


def number(value, pattern=".3f"):
    return "—" if value is None else format(value, pattern)


lines = ["# Local transport benchmark", "", "Independent JVM forks; medians across forks. "
         "Percentiles are computed inside each fork, then reported as a median (not a pooled percentile).", "",
         "| Scenario | Protocol | Success ops/s | p50 ms | p99 ms (min–max) | CPU µs/success | Alloc bytes/success | Failures/attempts |",
         "|---|---|---:|---:|---:|---:|---:|---:|"]
unexpected = 0
for name in [s["scenario"] for s in runs[0]["scenarios"]]:
    for protocol in ("http", "tcp"):
        rows = [s for r in runs if r["protocol"] == protocol for s in r["scenarios"] if s["scenario"] == name]
        if not rows:
            raise SystemExit(f"Missing scenario: {protocol} {name}")
        if "successes" in rows[0]:
            rate = number(median(s["successfulOpsPerSecond"] for s in rows), ",.0f")
            cpu = number(median(s["processCpuMillis"] * 1000 / s["successes"] for s in rows
                                if s["successes"] and s["processCpuMillis"] is not None), ".1f")
            alloc = number(median(s["allocatedBytes"] / s["successes"] for s in rows
                                  if s["successes"] and s["allocatedBytes"] is not None), ",.0f")
            failures = f'{sum(s["failures"] for s in rows)}/{sum(s["attempts"] for s in rows)}'
            if name != "overloaded_slow_peer":
                unexpected += sum(s["failures"] for s in rows)
        else:
            rate = cpu = alloc = "—"
            failures = f'{sum(s["failuresBeforeRecovery"] for s in rows)} retries / {sum(s["restarts"] for s in rows)} restarts'
        unexpected += sum(s.get("backgroundFailures", 0) for s in rows)
        p99s = [s["p99Millis"] for s in rows if s["p99Millis"] is not None]
        interval = "—" if not p99s else f'{number(median(p99s))} ({min(p99s):.3f}–{max(p99s):.3f})'
        lines.append(f'| {name} | {protocol} | {rate} | {number(median(s["p50Millis"] for s in rows))} | '
                     f'{interval} | {cpu} | {alloc} | {failures} |')
lines += ["", "CPU and allocations include all benchmark peers, background traffic, and the harness in the same JVM. "
          "Overload percentiles describe successful requests only; inspect failures before comparing rates. "
          "Raw JSON also records sampled heap, GC, background progress, and environment metadata."]
(root / "summary.md").write_text("\n".join(lines) + "\n")
(root / "combined.json").write_text(json.dumps(runs, indent=2) + "\n")
print("\n".join(lines))
if unexpected:
    raise SystemExit(f"Unexpected foreground/background failures: {unexpected}; inspect the raw reports")
