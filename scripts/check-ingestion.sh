#!/usr/bin/env bash
set -euo pipefail
# Defaults to the local project. Supply a compose project name for an isolated test stack.
cd "$(cd "$(dirname "$0")/.." && pwd)"
project="${1:-cloud-usage-billing}"
dc=(docker compose -p "$project" -f compose.ingestion.yaml)
failure_seconds="${INGESTION_FAILURE_SECONDS:-60}"
lag_seconds="${INGESTION_LAG_SECONDS:-86400}"
[[ "$failure_seconds" =~ ^[0-9]+$ && "$lag_seconds" =~ ^[0-9]+$ ]] || exit 1
# Exceptions are historical: alert only on failures after the last successful commit.
failed="$("${dc[@]}" exec -T clickhouse clickhouse-client --user billing_owner --password local-dev-only -q "
select if(count()=0,1,countIf(length(recent)>0 and dateDiff('second',arrayMin(recent),now()) >= $failure_seconds))
from (select arrayFilter(t -> t > last_commit_time, exceptions.time) as recent
      from system.kafka_consumers where database='billing' and table='usage_kafka')")"
if [[ "$failed" != 0 ]]; then echo 'ALERT ingestion consumer missing or persistently failing' >&2; exit 2; fi
"${dc[@]}" run --rm --no-deps -v "$PWD/config/local/kafka-admin.properties:/operator.properties:ro" --entrypoint java generator \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp '/app/lib/*' \
  io.github.bbororo5.cloudbilling.generator.LagProbe /operator.properties "$lag_seconds"
