#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
[[ "$#" == 0 || ( "$#" == 1 && "$1" == --initialize-new-group ) ]] || {
  echo 'Usage: bash scripts/start-ingestion.sh [--initialize-new-group]' >&2; exit 1;
}
docker compose up -d --wait kafka-1 kafka-2 kafka-3 clickhouse
docker compose run --rm kafka-init
admin=(docker compose exec -T kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka-1:9092 --command-config /config/kafka-admin.properties)
groups="$("${admin[@]}" --list)"
if [[ "${1:-}" == --initialize-new-group ]]; then
  if grep -Fxq usage-clickhouse-v1 <<< "$groups"; then
    echo 'Group already exists; refusing to reset its offsets. Restart without the initialization flag.' >&2; exit 1
  fi
  "${admin[@]}" --group usage-clickhouse-v1 --topic usage-events.v1 --reset-offsets --to-earliest --execute
elif ! grep -Fxq usage-clickhouse-v1 <<< "$groups"; then
  echo 'No group exists. For first installation only, pass --initialize-new-group.' >&2; exit 1
fi
for sql in database/clickhouse/ingestion.sql database/clickhouse/local-access.sql; do
  docker compose exec -T clickhouse clickhouse-client --user billing_owner --password local-dev-only --multiquery < "$sql"
done
