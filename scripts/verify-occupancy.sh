#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"
docker info >/dev/null
occupancy_container="$(docker run --detach --rm --label cloud-billing-purpose=occupancy-test \
  -e POSTGRES_USER=billing_owner -e POSTGRES_PASSWORD=local-dev-only -e POSTGRES_DB=billing \
  -p 127.0.0.1::5432 postgres:17.5-alpine)"
trap 'docker stop "$occupancy_container" >/dev/null' EXIT
for ((attempt=0; attempt<60; attempt++)); do
  if docker exec "$occupancy_container" pg_isready -U billing_owner -d billing >/dev/null; then break; fi
  sleep 1
done
for sql_file in database/postgresql/schema.sql database/postgresql/local-roles.sql database/postgresql/guard-privileges.sql \
    database/postgresql/occupancy.sql database/postgresql/occupancy.sql database/postgresql/occupancy_test.sql; do
  docker exec -i "$occupancy_container" psql -X -q -v ON_ERROR_STOP=1 -U billing_owner -d billing < "$sql_file"
done
occupancy_port="$(docker port "$occupancy_container" 5432/tcp)"
export OCCUPANCY_TEST_URL="jdbc:postgresql://127.0.0.1:${occupancy_port##*:}/billing"
./gradlew :apps:occupancy-worker:integrationTest --no-daemon "$@"
if [[ "${OCCUPANCY_KAFKA_TESTS:-false}" == true ]]; then
  ./gradlew :apps:occupancy-worker:kafkaTest --no-daemon
fi
