#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"

./gradlew test --no-daemon
docker compose config --quiet
docker compose up -d --wait

postgres_table="$(docker compose exec -T postgres \
  psql -U billing_owner -d billing -Atc \
  "select to_regclass('billing.billing_account')")"
test "$postgres_table" = "billing.billing_account"

docker compose exec -T postgres \
  psql -U billing_owner -d billing \
  < database/postgresql/local_roles_test.sql

clickhouse_tables="$(docker compose exec -T clickhouse \
  clickhouse-client --user billing_owner --password local-dev-only \
  --query "select count() from system.tables where database='billing'")"
test "$clickhouse_tables" = "2"

kafka_partitions="$(docker compose exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic usage-events.v1 \
  | sed -n '1s/.*PartitionCount: \([0-9][0-9]*\).*/\1/p')"
test "$kafka_partitions" = "12"

echo "foundation verification passed"
