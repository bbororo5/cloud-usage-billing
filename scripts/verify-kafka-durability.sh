#!/usr/bin/env bash
set -euo pipefail

topic="usage-events.v1"

describe_topic() {
  docker compose exec -T kafka-1 /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka-1:19092 --describe --topic "$topic"
}

wait_for_isr_count() {
  expected="$1"
  for _ in $(seq 1 30); do
    if describe_topic | awk -v expected="$expected" '
      /Partition: [0-9]+/ {
        split($0, section, "Isr: ")
        split(section[2], tail, "\t")
        count = split(tail[1], replicas, ",")
        if (count != expected) bad = 1
      }
      END { exit bad }
    '; then
      return 0
    fi
    sleep 1
  done
  echo "ISR count did not converge to $expected" >&2
  return 1
}

produce() {
  docker compose exec -T kafka-1 /opt/kafka/bin/kafka-verifiable-producer.sh \
    --bootstrap-server kafka-1:19092 \
    --topic "$topic" \
    --max-messages 1 \
    --acks -1 | grep -q '"acked":1'
}

set_min_isr() {
  value="$1"
  docker compose exec -T kafka-1 /opt/kafka/bin/kafka-configs.sh \
    --bootstrap-server kafka-1:19092 \
    --alter \
    --entity-type topics \
    --entity-name "$topic" \
    --add-config "min.insync.replicas=$value" >/dev/null
}

restore_cluster() {
  docker compose start kafka-3 >/dev/null 2>&1 || true
  set_min_isr 2 >/dev/null 2>&1 || true
}
trap restore_cluster EXIT

description="$(describe_topic)"
grep -q 'ReplicationFactor: 3' <<<"$description"
grep -q 'min.insync.replicas=2' <<<"$description"

wait_for_isr_count 3
produce
docker compose stop kafka-3 >/dev/null
wait_for_isr_count 2
produce

set_min_isr 3
if produce >/dev/null 2>&1; then
  echo "write unexpectedly succeeded below min.insync.replicas" >&2
  exit 1
fi
set_min_isr 2

echo "kafka durability checks passed"
