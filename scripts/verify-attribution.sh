#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
docker info >/dev/null
attribution_pg="$(docker run -d --rm --label cloud-billing-purpose=attribution-test \
  -e POSTGRES_USER=billing_owner -e POSTGRES_PASSWORD=local-dev-only -e POSTGRES_DB=billing \
  -p 127.0.0.1::5432 postgres:17.5-alpine)"
attribution_ch=""
cleanup() {
  docker stop "$attribution_pg" >/dev/null
  if [[ -n "$attribution_ch" ]]; then docker stop "$attribution_ch" >/dev/null; fi
}
trap cleanup EXIT
attribution_ch="$(docker run -d --rm --label cloud-billing-purpose=attribution-test \
  -e CLICKHOUSE_SKIP_USER_SETUP=1 -p 127.0.0.1::8123 clickhouse/clickhouse-server:26.3)"
for ((attempt=0; attempt<60; attempt++)); do
  if docker exec "$attribution_pg" pg_isready -U billing_owner -d billing >/dev/null \
    && docker exec "$attribution_ch" clickhouse-client -q 'select 1' >/dev/null 2>&1; then break; fi
  sleep 1
done
for sql in database/postgresql/schema.sql database/postgresql/local-roles.sql database/postgresql/guard-privileges.sql \
  database/postgresql/occupancy.sql database/postgresql/attribution.sql database/postgresql/attribution.sql; do
  docker exec -i "$attribution_pg" psql -X -q -v ON_ERROR_STOP=1 -U billing_owner -d billing < "$sql"
done
for sql in database/clickhouse/schema.sql database/clickhouse/local-access.sql database/clickhouse/attribution.sql database/clickhouse/attribution.sql; do
  docker exec -i "$attribution_ch" clickhouse-client --multiquery < "$sql"
done
attribution_pg_port="$(docker port "$attribution_pg" 5432/tcp)"
attribution_ch_port="$(docker port "$attribution_ch" 8123/tcp)"
export OCCUPANCY_TEST_URL="jdbc:postgresql://127.0.0.1:${attribution_pg_port##*:}/billing"
export ATTRIBUTION_TEST_CH="http://127.0.0.1:${attribution_ch_port##*:}"
./gradlew :apps:occupancy-worker:attributionTest --no-daemon "$@"
