# Cloud Usage Billing

클라우드 사용량 이벤트를 수집·집계하고 최근 집계 기준의 월 누적 예상 금액과 월간 확정 금액을 제공하는 포트폴리오 프로젝트입니다.

실행 기반을 만들었으며 Kafka → ClickHouse 직접 소비 전환과 회사 귀속 모델을 검증·설계하는 단계입니다. 목표 구조와 현재 코드는 아직 다릅니다.

## 개발 환경

- Java 21
- Spring Boot 4.1
- PostgreSQL 17, Apache Kafka 4.3, ClickHouse 26.3 LTS

## 로컬 실행 기반

```bash
./scripts/verify-foundation.sh
```

애플리케이션은 `apps` 아래의 실행 단위별 Gradle 모듈로 구성한다.

## 문서

- [C2 컨테이너 다이어그램 (SVG)](docs/diagrams/c2-containers.svg) — 발생기 → Kafka → ClickHouse 직접 소비의 목표 구조입니다. 검증·격리·복구 실험과 코드 전환은 남아 있습니다.
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
