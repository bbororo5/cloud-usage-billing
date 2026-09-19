#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"
docker info >/dev/null

# No existing database, named volume, or fixed host port is used.
access_container="$(docker run --detach --rm \
  --label cloud-billing-purpose=postgres-access-test \
  -e POSTGRES_USER=billing_owner -e POSTGRES_PASSWORD=local-dev-only \
  -e POSTGRES_DB=billing -p 127.0.0.1::5432 postgres:17.5-alpine)"
cleanup() { docker stop "$access_container" >/dev/null; }
trap cleanup EXIT

access_ready=false
for ((attempt=0; attempt<60; attempt++)); do
  if docker exec "$access_container" pg_isready -h 127.0.0.1 -U billing_owner -d billing >/dev/null 2>&1; then
    access_ready=true
    break
  fi
  sleep 1
done
if [[ "$access_ready" != true ]]; then
  docker logs "$access_container"
  exit 1
fi

docker exec "$access_container" createdb -U billing_owner schema_regression
for test_database in billing schema_regression; do
  for sql_file in database/postgresql/schema.sql database/postgresql/local-roles.sql database/postgresql/guard-privileges.sql; do
    docker exec -i "$access_container" psql -X -q -v ON_ERROR_STOP=1 -U billing_owner -d "$test_database" < "$sql_file"
  done
done
docker exec -i "$access_container" psql -X -q -v ON_ERROR_STOP=1 -U billing_owner -d schema_regression < database/postgresql/schema_test.sql
for sql_file in database/postgresql/local_roles_test.sql tests/postgres-access/fixtures.sql; do
  docker exec -i "$access_container" psql -X -q -v ON_ERROR_STOP=1 -U billing_owner -d billing < "$sql_file"
done
access_port="$(docker port "$access_container" 5432/tcp)"
export BILLING_ACCESS_TEST_URL="jdbc:postgresql://127.0.0.1:${access_port##*:}/billing"
./gradlew :tests:postgres-access:accessTest --no-daemon "$@"
