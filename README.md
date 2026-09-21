# Cloud Usage Billing

클라우드 사용량 이벤트를 수집·집계하고 최근 집계 기준의 월 누적 예상 금액과 월간 확정 금액을 제공하는 포트폴리오 프로젝트입니다.

사용량 발생기 → Kafka → ClickHouse 원장·중복 제거 뷰를 구현했습니다. 현재 단계는 수집 통합·장애 검증이며 회사 귀속과 비용 API는 후속 작업입니다.

## 개발 환경

- Java 21
- Spring Boot 4.1
- PostgreSQL 17, Apache Kafka 4.3, ClickHouse 26.3 LTS

## 실행·검증

```bash
./gradlew check --no-daemon
bash scripts/verify-clickhouse-storage.sh
bash scripts/verify-ingestion.sh
```

통합 검증은 독립 Docker 프로젝트를 만들고 끝나면 해당 테스트 볼륨만 삭제합니다. 실행법·장애 실험의 한계는 [수집 구현·검증](docs/ingestion-implementation.md)을 참고하세요. 기존 HTTP 접수·Java 적재기는 `legacy/`에 보관하며 빌드에서 제외했습니다.

## 문서

- [C2 컨테이너 다이어그램 (SVG)](docs/diagrams/c2-containers.svg) — 직접 소비 경로와 후속 귀속·조회·배치의 목표입니다.
- [프로젝트 요구사항](docs/requirements.md)
- [품질 시나리오](docs/quality-scenarios.md)
- [Architecture Drivers](docs/architecture-driver.md)
- [Architecture Test Plan](docs/architecture-test-plan.md)
- [Architecture](docs/architecture.md)
- [Logical Data Model — 소유권·관계·규칙](docs/logical-data-model.md)
- [PostgreSQL Physical Data Model](docs/postgresql-physical-data-model.md)
- [ClickHouse Physical Data Model](docs/clickhouse-physical-data-model.md)
- [Storage Access Contract](docs/storage-access-contract.md)
- [Implementation Roadmap](docs/implementation-roadmap.md)
- [Event Contract v1](docs/event-contract.md)
- [API Contract v1](docs/api-contract.md)
- [ADR Roadmap](docs/adr/README.md)
