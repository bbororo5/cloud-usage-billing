# ADR Roadmap

중요한 기술 선택만 ADR로 남기며, 필요한 시점에 하나씩 결정한다.

| ADR | 의제 | 시점 | 상태 |
|---|---|---|---|
| [001](0001-execution-unit-structure.md) | 실행 단위 구성 | 논리 아키텍처 확정 후 | 부분 대체 |
| [002](0002-event-streaming-for-realtime-aggregation.md) | 실시간 비용 집계를 위한 이벤트 스트리밍 | 이벤트 접수 구현 전 | 승인 |
| [003](0003-realtime-cost-aggregation-and-query.md) | 실시간 대시보드를 위한 비용 집계·조회 | 집계 구현 전 | 승인 |
| [004](0004-monthly-validation-and-finalization-batch.md) | 월간 검증·확정 배치 | 정산 구현 전 | 승인 |
| [005](0005-authentication-state-management.md) | 인증 상태 관리 | 로그인 구현 전 | 승인 |
| [006](0006-tenant-rbac-enforcement.md) | 테넌트·RBAC 강제 방식 | 사용자 API 구현 전 | 승인 |
| [007](0007-usage-ledger-writer-execution-unit.md) | Kafka에서 ClickHouse로 원장을 적재하는 실행 단위 | 물리 데이터 흐름 확정 후 | 승인 |

캐시와 배포 환경은 성능 측정이나 실제 배포에서 중요한 선택이 생길 때만 ADR로 추가한다.

ADR은 `상태 → 배경 → 요구사항 → 선택지 → 트레이드오프 분석 → 결정 → 결과`를 기본으로 하며, 선택지가 사실상 없는 경우 불필요한 목차는 생략한다.
