# 이전 수집 구현

접수 HTTP API·Java 원장 적재기·이전 이벤트 파서는 ADR-010으로 대체되어 빌드에서 제외했다. 학습·변경 이력 비교용이며 실행 대상이 아니다.

현재 경로는 `apps/usage-generator` → Kafka → `database/clickhouse/ingestion.sql`이다. 이전 코드는 서비스별 행과 이전 이벤트 규칙을 전제하므로 현재 원장에 연결하지 않는다. 기존 데이터 볼륨은 이 이동으로 변경하거나 삭제하지 않았다.
