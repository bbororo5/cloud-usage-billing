#!/usr/bin/env bash
set -euo pipefail
bin=/opt/kafka/bin
admin=(--bootstrap-server kafka-1:9092 --command-config /config/kafka-admin.properties)
"$bin/kafka-topics.sh" "${admin[@]}" --create --if-not-exists --topic instance-occupancy-events.v1 \
  --partitions 3 --replication-factor 3 --config min.insync.replicas=2 \
  --config retention.ms=604800000 --config retention.bytes=-1 --config cleanup.policy=delete
"$bin/kafka-acls.sh" "${admin[@]}" --add --allow-principal User:occupancy-worker \
  --operation Read --operation Describe --topic instance-occupancy-events.v1
"$bin/kafka-acls.sh" "${admin[@]}" --add --allow-principal User:occupancy-worker \
  --operation Read --group occupancy-worker-v1
"$bin/kafka-acls.sh" "${admin[@]}" --add --allow-principal User:occupancy-infra \
  --operation Write --operation Describe --topic instance-occupancy-events.v1
"$bin/kafka-acls.sh" "${admin[@]}" --add --allow-principal User:occupancy-infra \
  --operation IdempotentWrite --cluster
# Do not reset offsets or change the existing usage topic.
