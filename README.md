# Cloud Usage Billing

클라우드 사용량 이벤트를 수집·집계하고 최근 집계 기준의 월 누적 예상 금액과 월간 확정 금액을 제공하는 포트폴리오 프로젝트입니다.

아키텍처·계약·물리 데이터 모델을 확정하고 실행 가능한 구현 기반을 만드는 단계입니다.

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

- [프로젝트 요구사항](docs/requirements.md)
- [품질 시나리오](docs/quality-scenarios.md)
- [Architecture Drivers](docs/architecture-driver.md)
- [Architecture Test Plan](docs/architecture-test-plan.md)
- [Architecture](docs/architecture.md)
- [Data Ownership](docs/data-ownership.md)
- [Logical Data Model](docs/logical-data-model.md)
- [PostgreSQL Physical Data Model](docs/postgresql-physical-data-model.md)
- [ClickHouse Physical Data Model](docs/clickhouse-physical-data-model.md)
- [Storage Access Contract](docs/storage-access-contract.md)
- [Implementation Roadmap](docs/implementation-roadmap.md)
- [Event Contract v1](docs/event-contract.md)
- [API Contract v1](docs/api-contract.md)
- [ADR Roadmap](docs/adr/README.md)
