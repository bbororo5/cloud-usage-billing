# ADR Roadmap

중요한 기술 선택만 ADR로 남기며, 필요한 시점에 하나씩 결정한다.

| ADR | 의제 | 시점 | 상태 |
|---|---|---|---|
| [001](0001-execution-unit-structure.md) | 실행 단위 구성 | 논리 아키텍처 확정 후 | 부분 대체 |
| [002](0002-event-streaming-for-realtime-aggregation.md) | 실시간 비용 집계를 위한 이벤트 스트리밍 | 이벤트 접수 구현 전 | 부분 변경 |
| [003](0003-realtime-cost-aggregation-and-query.md) | 실시간 대시보드를 위한 비용 집계·조회 | 집계 구현 전 | 승인 |
| [004](0004-monthly-validation-and-finalization-batch.md) | 월간 검증·확정 배치 | 정산 구현 전 | 승인 |
| [005](0005-authentication-state-management.md) | 인증 상태 관리 | 로그인 구현 전 | 승인 |
| [006](0006-tenant-rbac-enforcement.md) | 테넌트·RBAC 강제 방식 | 사용자 API 구현 전 | 승인 |
| [007](0007-usage-ledger-writer-execution-unit.md) | Kafka에서 ClickHouse로 원장을 적재하는 실행 단위 | 물리 데이터 흐름 확정 후 | 승인 |
| [008](0008-tenant-neutral-usage-ingestion.md) | 과금 비인지 사용량 수집과 후행 귀속 | VM 이벤트 경계 재검토 후 | 승인 |
| [009](0009-occupancy-event-recovery-and-cache-consistency.md) | 점유 변경의 Kafka 기반 복구와 캐시 일관성 | 점유 생명주기 기획 후 | 승인·구현 전 |
| [010](0010-direct-kafka-ingestion.md) | 사용량 발생기의 Kafka 직접 발행 | 접수 계층 단순화 검토 후 | 승인·코드 전환 전 |

점유 캐시의 일관성 원칙은 ADR-009에서 정한다. 캐시 제품과 배포 환경은 구체적인 구현 선택이 필요할 때 결정한다.

ADR은 `상태 → 배경 → 요구사항 → 선택지 → 트레이드오프 분석 → 결정 → 결과`를 기본으로 하며, 선택지가 사실상 없는 경우 불필요한 목차는 생략한다.
