#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
benchmark_output="${1:-target/transport-benchmark}"
benchmark_trials="${BENCHMARK_TRIALS:-3}"
benchmark_samples="${BENCHMARK_SAMPLES:-10000}"
benchmark_warmup="${BENCHMARK_WARMUP:-2000}"
benchmark_concurrency="${BENCHMARK_CONCURRENCY:-8}"
benchmark_tls="${BENCHMARK_TLS:-true}"
for benchmark_number in "$benchmark_trials" "$benchmark_samples" "$benchmark_warmup" "$benchmark_concurrency"; do
  if [[ ! "$benchmark_number" =~ ^[1-9][0-9]*$ ]]; then
    printf 'Trial, sample, warmup and concurrency counts must be positive integers.\n' >&2
    exit 2
  fi
done
if [[ "$benchmark_tls" != true && "$benchmark_tls" != false ]]; then
  printf 'BENCHMARK_TLS must be true or false.\n' >&2
  exit 2
fi
benchmark_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mkdir -p "$benchmark_output"
if compgen -G "$benchmark_output/http-*.json" > /dev/null || compgen -G "$benchmark_output/tcp-*.json" > /dev/null; then
  printf 'Choose an output directory without existing benchmark JSON reports.\n' >&2
  exit 2
fi
mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/benchmark-classpath.txt -DincludeScope=test
benchmark_classpath="target/test-classes:target/classes:$(cat target/benchmark-classpath.txt)"
for ((benchmark_trial=1; benchmark_trial<=benchmark_trials; benchmark_trial++)); do
  if ((benchmark_trial % 2 == 1)); then benchmark_protocols="http tcp"; else benchmark_protocols="tcp http"; fi
  for benchmark_protocol in $benchmark_protocols; do
    "$benchmark_java" -Xms256m -Xmx256m -XX:ActiveProcessorCount=4 \
      -Dvertx.logger-delegate-factory-class-name=io.vertx.core.logging.Log4j2LogDelegateFactory \
      -Dlog4j.configurationFile=src/test/resources/log4j2-process-test.xml \
      -cp "$benchmark_classpath" com.inqwise.leader.TransportBenchmark \
      "$benchmark_protocol" "$benchmark_tls" "$benchmark_output/$benchmark_protocol-$benchmark_trial.json" \
      "$benchmark_samples" "$benchmark_warmup" "$benchmark_concurrency" "$benchmark_trial" \
      > "$benchmark_output/$benchmark_protocol-$benchmark_trial.stdout" \
      2> "$benchmark_output/$benchmark_protocol-$benchmark_trial.stderr"
    printf '%s trial %s finished\n' "$benchmark_protocol" "$benchmark_trial"
  done
done
python3 benchmarks/summarize.py "$benchmark_output"
