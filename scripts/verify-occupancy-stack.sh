#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
./gradlew :apps:occupancy-worker:bootJar --no-daemon
project="billing-occupancy-test-$(date +%s)-$$"
dc=(docker compose -p "$project" -f compose.yaml -f compose.occupancy.yaml -f compose.occupancy-test.yaml)
cleanup() {
  result=$?
  if [[ "$result" != 0 ]]; then "${dc[@]}" logs --tail 40 occupancy-worker postgres >&2 || true; fi
  "${dc[@]}" down --volumes >/dev/null
}
trap cleanup EXIT
"${dc[@]}" up -d --wait kafka-1 kafka-2 kafka-3 postgres
"${dc[@]}" run --rm occupancy-init
"${dc[@]}" up -d --no-deps occupancy-worker
pg() { "${dc[@]}" exec -T postgres psql -X -qAt -v ON_ERROR_STOP=1 -U billing_owner -d billing -c "$1"; }
pg "insert into billing.billing_account(billing_account_id,billing_account_name) values('company-x','Fixture company')"
for kind in initialized started ended confirmed; do
  payload="$(tr -d '\n' < "contracts/examples/occupancy/$kind.json")"
  printf 'urn:vm:a\t%s\n' "$payload" | "${dc[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server kafka-1:9092 --command-config /config/kafka-occupancy-producer.properties \
    --topic instance-occupancy-events.v1 --property parse.key=true
done
ready=false
for ((attempt=0;attempt<60;attempt++)); do
  if [[ "$(pg "select last_applied_sequence from billing.occupancy_stream where source='urn:vm:a'")" == 4 ]]; then ready=true;break;fi
  sleep 1
done
[[ "$ready" == true ]]
[[ "$(pg 'select count(*) from billing.occupancy_interval')" == 1 ]]
denial="$(printf '{}\n' | "${dc[@]}" exec -T kafka-1 /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka-1:9092 --command-config /config/kafka-occupancy-worker.properties \
  --topic instance-occupancy-events.v1 --command-property enable.idempotence=false \
  --command-property max.block.ms=5000 --command-property delivery.timeout.ms=5000 \
  --command-property request.timeout.ms=1000 2>&1 || true)"
[[ "$denial" == *TopicAuthorizationException* ]]
echo 'PASS authenticated occupancy stack, history application and consumer write denial'
