#!/usr/bin/env bash
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
storage_container="$(docker run -d --rm --label cloud-billing-purpose=storage-test \
  -e CLICKHOUSE_SKIP_USER_SETUP=1 clickhouse/clickhouse-server:26.3)"
trap 'docker stop "$storage_container" >/dev/null' EXIT
ready=false
for ((attempt=0; attempt<60; attempt++)); do
  if docker exec "$storage_container" clickhouse-client -q 'select 1' >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 1
done
[[ "$ready" == true ]] || { docker logs "$storage_container"; exit 1; }
for sql in database/clickhouse/schema.sql database/clickhouse/schema_test.sql; do
  docker exec -i "$storage_container" clickhouse-client --multiquery < "$sql"
done
