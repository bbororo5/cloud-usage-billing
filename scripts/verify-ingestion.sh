#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
./gradlew :apps:usage-generator:test :apps:usage-generator:installDist --no-daemon
# Own project, network and volumes only. Never attaches to the developer's compose project.
project="billing-ingestion-test-$(date +%s)-$$"
dc=(docker compose -p "$project" -f compose.ingestion.yaml)
cleanup() {
  result=$?
  if [[ "$result" != 0 ]]; then "${dc[@]}" logs --tail 40 clickhouse >&2 || true; fi
  "${dc[@]}" down --volumes >/dev/null
}
trap cleanup EXIT
"${dc[@]}" up -d --wait kafka-1 kafka-2 kafka-3 clickhouse
"${dc[@]}" run --rm kafka-init
admin=(--bootstrap-server kafka-1:9092 --command-config /config/kafka-admin.properties)
kafka() { "${dc[@]}" exec -T kafka-1 "/opt/kafka/bin/$1" "${admin[@]}" "${@:2}"; }
ch() { "${dc[@]}" exec -T clickhouse clickhouse-client --user billing_owner --password local-dev-only -q "$1"; }
sql_file() { "${dc[@]}" exec -T clickhouse clickhouse-client --user billing_owner --password local-dev-only --multiquery < "$1"; }
generate() {
  "${dc[@]}" run --rm --no-deps -e JAVA_OPTS=-Dorg.slf4j.simpleLogger.defaultLogLevel=warn generator \
    --config /config/kafka-producer.properties --source "$1" --resource "$1" \
    --from 2026-08-12T00:00:35.123Z --seconds "$2" --pace-ms 0 \
    --network-bytes 18446744073709551615
}
wait_query() {
  for ((i=0;i<60;i++)); do
    if [[ "$(ch "$1" 2>/dev/null)" == "$2" ]]; then return; fi
    sleep 1
  done
  echo "Query did not reach expected result $2: $1" >&2; exit 1
}
offset_sum() {
  kafka kafka-consumer-groups.sh --group usage-clickhouse-v1 --describe 2>/dev/null |
    awk '$1=="usage-clickhouse-v1" && $2=="usage-events.v1" {if($4 !~ /^[0-9]+$/) bad=1; n++; s+=$4} END {if(n!=12 || bad) exit 1; print s+0}'
}
wait_offsets() {
  for ((j=0;j<30;j++)); do
    if [[ "$(offset_sum)" == "$1" ]]; then return; fi
    sleep 1
  done
  echo "Offsets did not reach $1" >&2; exit 1
}
# Fresh test group only; resetting a production group is NOT part of startup.
kafka kafka-consumer-groups.sh --group usage-clickhouse-v1 --topic usage-events.v1 --reset-offsets --to-earliest --execute
sql_file database/clickhouse/ingestion.sql
sql_file database/clickhouse/local-access.sql

generate normal 180
wait_query 'select count() from billing.usage_event' 3
wait_offsets 3
ch "select throwIf(countIf(length(measurements)=3 and measurements[3].quantity=toUInt64('18446744073709551615') and toUnixTimestamp64Milli(event_time)%1000=123)!=3,'mapping or precision mismatch') from billing.usage_event"
generate normal 180
wait_offsets 6
[[ "$(ch 'select count() from billing.usage_event')" == 3 ]]
[[ "$(ch 'select count() from billing.usage_record_delivery')" -ge 6 ]]
echo 'PASS mapping, precision, repeated publication and deduplication'

ch 'alter table billing.usage_record_delivery add constraint injected_write_failure check 0'
generate blocked 60
wait_query "select countIf(length(exceptions.text)>0) from system.kafka_consumers where database='billing'" 1
sleep 3
[[ "$(offset_sum)" == 6 ]]
[[ "$(ch 'select count() from billing.usage_event')" == 3 ]]
ch 'alter table billing.usage_record_delivery drop constraint injected_write_failure'
wait_offsets 7
wait_query 'select count() from billing.usage_event' 4
echo 'PASS failed insert holds offsets; recovery keeps all events'
bash scripts/check-ingestion.sh "$project"

"${dc[@]}" kill -s SIGKILL clickhouse
generate offline 120
if "${dc[@]}" run --rm --no-deps -v "$PWD/config/local/kafka-admin.properties:/operator.properties:ro" --entrypoint java generator \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp '/app/lib/*' \
  io.github.bbororo5.cloudbilling.generator.LagProbe /operator.properties 0; then
  echo 'Expected backlog age alert' >&2; exit 1
else
  [[ "$?" == 2 ]]
fi
"${dc[@]}" up -d --wait clickhouse
wait_offsets 9
wait_query 'select count() from billing.usage_event' 6
echo 'PASS process crash, backlog and committed-position recovery (not power-loss proof)'

# Deterministic replay of the durable-insert/unknown-commit outcome, not a power-loss simulation.
ch 'detach table billing.usage_kafka'
kafka kafka-consumer-groups.sh --group usage-clickhouse-v1 --topic usage-events.v1 --reset-offsets --to-earliest --execute
ch 'attach table billing.usage_kafka'
wait_offsets 9
[[ "$(ch 'select count() from billing.usage_event')" == 6 ]]
[[ "$(ch 'select count() from billing.usage_record_delivery')" -ge 18 ]]
echo 'PASS replay from older offsets does not double-count'

"${dc[@]}" exec -T clickhouse clickhouse-client --user billing_bff --password local-bff-only -q 'select 1'
for table in usage_record_delivery usage_event usage_kafka; do
  denial="$("${dc[@]}" exec -T clickhouse clickhouse-client --user billing_bff --password local-bff-only -q "select count() from billing.$table" 2>&1 || true)"
  [[ "$denial" == *ACCESS_DENIED* ]]
done
echo 'PASS BFF cannot access raw events, deduplication view or Kafka table'

# An authenticated producer cannot write another topic; an ingestion identity cannot produce.
kafka kafka-topics.sh --create --topic forbidden --partitions 1 --replication-factor 3
for pair in 'kafka-producer.properties forbidden' 'kafka-consumer.properties usage-events.v1'; do
  read -r config topic <<< "$pair"
  denial="$(printf '{}\n' | "${dc[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server kafka-1:9092 --producer.config "/config/$config" --topic "$topic" \
    --producer-property max.block.ms=5000 --producer-property delivery.timeout.ms=5000 \
    --producer-property request.timeout.ms=1000 2>&1 || true)"
  [[ "$denial" == *TopicAuthorizationException* ]]
done
denial="$(printf '{}\n' | "${dc[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --producer.config /config/kafka-producer.properties --topic usage-events.v1 \
  --producer-property 'sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="generator" password="wrong";' \
  --producer-property max.block.ms=5000 2>&1 || true)"
[[ "$denial" == *SaslAuthenticationException* ]]
echo 'PASS broker authentication and least-privilege topic ACLs'

wait_isr() {
  for ((attempt=0;attempt<30;attempt++)); do
    if kafka kafka-topics.sh --describe --topic usage-events.v1 | awk -v expected="$1" '
      /Partition: [0-9]+/ {n++; split($0,a,"Isr: "); split(a[2],b,"\t"); if(split(b[1],c,",")!=expected) bad=1}
      END {exit n!=12 || bad}'; then return; fi
    sleep 1
  done
  echo "ISR did not reach $1" >&2; exit 1
}
"${dc[@]}" stop kafka-3
wait_isr 2
generate replica-loss 60
wait_offsets 10
wait_query 'select count() from billing.usage_event' 7
kafka kafka-configs.sh --alter --entity-type topics --entity-name usage-events.v1 --add-config min.insync.replicas=3
denial="$( { tr -d '\n' < contracts/v1/examples/instance-usage-event.json; printf '\n'; } | "${dc[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --producer.config /config/kafka-producer.properties --topic usage-events.v1 --sync \
  --producer-property acks=all --producer-property delivery.timeout.ms=5000 \
  --producer-property request.timeout.ms=1000 2>&1 || true)"
[[ "$denial" == *NotEnoughReplicas* || "$denial" == *TimeoutException* ]]
kafka kafka-configs.sh --alter --entity-type topics --entity-name usage-events.v1 --add-config min.insync.replicas=2
"${dc[@]}" up -d --wait kafka-3
wait_isr 3
echo 'PASS replica loss remains available at ISR=2; insufficient ISR rejects writes'

# A malformed message remains uncommitted; no skip/dead-letter mode is allowed.
printf '{broken-json}\n' | "${dc[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --producer.config /config/kafka-admin.properties --topic usage-events.v1 \
  --producer-property acks=all
sleep 5
[[ "$(offset_sum)" == 10 ]]
[[ "$(ch 'select count() from billing.usage_event')" == 7 ]]
ch "select throwIf(countIf(length(exceptions.text)>0)=0,'parser failure was not observable') from system.kafka_consumers where database='billing'"
echo 'PASS malformed input is not silently discarded'
if INGESTION_FAILURE_SECONDS=0 bash scripts/check-ingestion.sh "$project"; then
  echo 'Expected consumer failure alert' >&2; exit 1
else
  [[ "$?" == 2 ]]
fi
echo 'PASS persistent failure alert'
echo 'ingestion integration tests passed'
