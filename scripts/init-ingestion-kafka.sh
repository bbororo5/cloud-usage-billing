#!/usr/bin/env bash
set -euo pipefail
bin=/opt/kafka/bin
admin=(--bootstrap-server kafka-1:9092 --command-config /config/kafka-admin.properties)
"$bin/kafka-topics.sh" "${admin[@]}" --create --if-not-exists --topic usage-events.v1 \
  --partitions 12 --replication-factor 3 --config min.insync.replicas=2 \
  --config retention.ms=604800000 --config retention.bytes=-1 --config cleanup.policy=delete
"$bin/kafka-acls.sh" "${admin[@]}" --add --allow-principal User:generator \
  --operation Write --operation Describe --topic usage-events.v1
"$bin/kafka-acls.sh" "${admin[@]}" --add --allow-principal User:generator \
  --operation IdempotentWrite --cluster
"$bin/kafka-acls.sh" "${admin[@]}" --add --allow-principal User:ingestion \
  --operation Read --operation Describe --topic usage-events.v1
"$bin/kafka-acls.sh" "${admin[@]}" --add --allow-principal User:ingestion \
  --operation Read --group usage-clickhouse-v1
# Bootstrap the new group deliberately. Never reset an existing group's offsets on startup.
# Initial offsets are initialized separately by the verification/first-install procedure.
