# 작업별 설계·구현·검증 상태

## 1. 판정 기준

[작업 분해](implementation-roadmap.md#2-작업-분해-초안)의 각 말단 항목을 같은 순서로 대조했다. 기준은 `c512847`이며 2026-09-22에 코드·테스트와 [해당 커밋의 CI 성공](https://github.com/bbororo5/cloud-usage-billing/actions/runs/35619839126)을 확인했다. 이번 점검에서는 제품 테스트를 재실행하지 않았다.

2026-09-23: 외부 VM 제어 경계를 반영해 내부 활성화·요청 만료 작업을 점유 사실 수신·이력 반영으로 교체했다. 새 계약·업무 코드·테스트가 완성된 것은 아니며 위 CI는 기존 수집·DB 검사 근거다.

- **설계:** `완료`는 해당 작업의 기준 설계가 있음, `부분`은 계약·방향만 있고 세부 결정이 남음, `요구`는 기능 요구만 있음.
- **구현:** `완료`는 해당 작업 코드가 있음, `부분`은 일부 구성만 있음, `없음`은 업무 코드가 없음. 실행 뼈대는 기능 구현으로 세지 않는다.
- **검증:** `통과`도 표에 적힌 테스트 범위만 의미한다. `부분`은 필요한 검사 중 일부만 통과, `없음`은 기능 검증 근거가 없음.

전체 완료율은 계산하지 않는다. 단위·계약·DB 검사 통과를 사용자 기능 전체의 통과로 확대하지 않는다.

## 2. 컨테이너별 상태

### 2.1 VM 발생기 — 외부

설계: [이벤트 계약][event]·[수집 구현][ingestion]. 완료 범위는 명시한 점유 구간의 시뮬레이터이며 실제 VM 제어·측정은 아니다.

| 작업 | 설계 | 구현 | 검증·근거 |
|---|---|---|---|
| 점유 구간 → 60초 이벤트 | 완료 | [완료][generator] | 부분: [형식·정밀도·잔여 구간][generator-test] 통과. 실제 점유 상태 연결은 없음 |
| Kafka 발행·ACK 처리 | 완료 | [완료][publisher] | 통과: [ACK 전 성공 금지][generator-test]·[실제 전송][integration] |
| 동일 이벤트 재전송 | 완료 | [완료][publisher] | 통과: [응답 불명 시 같은 바이트][generator-test]·[계획 재발행][integration]. 재시작은 같은 입력 계획 보존 전제 |

### 2.2 Kafka

설계: [스트리밍 ADR][streaming]·[수집 구현][ingestion]. 아래 구현 완료는 로컬 구성에 한정한다.

| 작업 | 설계 | 구현 | 검증·근거 |
|---|---|---|---|
| 사용량 토픽·복제·ACK 설정 | 완료 | [완료][kafka-init] | 통과: [복제본 중단·ISR 부족 시 거부][integration] |
| 발행·소비 권한 제한 | 완료 | [완료][kafka-init] | 통과: [잘못된 인증·토픽 쓰기 거부][integration]. 운영 TLS는 별도 |
| 보관·소비 위치 정책 | 완료 | [완료][startup]·[소비 설정][kafka-config] | 부분: [재시작·이전 offset 재생][integration] 통과. 실제 보관 만료·범위 이탈은 미검증 |
| 점유 사실 토픽 | 부분 | 없음 | 없음: [외부 확정 사실·별도 토픽 원칙][occupancy-adr]만 있음 |

### 2.3 ClickHouse

설계: [물리 모델][ch-model]·[저장소 계약][storage]. 귀속 모델이 없는 상태를 원시 원장 완성으로 대신하지 않는다.

| 작업 | 설계 | 구현 | 검증·근거 |
|---|---|---|---|
| 이벤트 적재·commit 연결 | 완료 | [완료][kafka-input] | 부분: [저장 실패·프로세스 재시작][integration] 통과. 전원 장애·fsync 실패 주입 없음 |
| 일반 뷰 중복 제거 | 완료 | [완료][ch-schema] | 통과: [다른 묶음·역순·source 분리·세 측정값 보존][ch-test]·[재전달][integration] |
| 귀속 결과 저장·정정 | 부분 | 없음 | 없음: 물리 형태·재귀속 공개 절차 미결 |
| 테넌트별 신원·Row Policy | 부분 | [부분][ch-access] | 부분: [BFF 원장·뷰 접근 거부][integration]만 통과. 회사별 허용·차단은 없음 |
| 가격 사본 적재·버전 확인 | 부분 | [부분][ch-schema] | 부분: [최신 버전 선택][ch-test]만 통과. 동기화·누락·버전 대조는 없음 |
| 기간·서비스·자원별 집계 SQL | 부분 | 없음 | 없음: 귀속 모델·시간 경계 미결, [조회 SQL 미작성][queries] |
| 귀속 완료 사용량 커서 조회 | 부분 | 없음 | 없음: [커서 계약][api]만 있음 |

### 2.4 PostgreSQL

설계: [물리 모델][pg-model]. 저장소 제약과 이를 호출하는 애플리케이션 기능을 구분한다.

| 작업 | 설계 | 구현 | 검증·근거 |
|---|---|---|---|
| 사용자·소속·역할 제약 | 완료 | [완료][pg-schema]·[함수 권한][guards] | 부분: [마지막 Admin 보호·교차 회사 쓰기 거부][pg-test] 통과. 동시 Admin 변경 경합은 미검증 |
| 점유 이력·버전 제약 | 부분 | 없음 | 없음: [논리 규칙][logical]만 있고 테이블·제약 없음 |
| 가격 원본·export | 완료 | [완료][pg-schema] | 부분: [가격 겹침 거부·export 행 수][pg-sql-test]·[읽기 권한][pg-test] 통과. 버전 단조성 등 전체 규칙은 미검증 |
| 정산 실행·확정 결과 보존 | 완료 | [완료][pg-schema]·[함수 권한][guards] | 부분: [중복 실행 상태·완료 결과 수정 거부][pg-sql-test]·[확정/차이 거부][pg-test] 통과. 동시 실행 경합은 미검증 |
| 앱 권한·RLS | 완료 | [완료][pg-roles]·[함수 권한][guards] | 부분: [실제 계정 접근 112건][pg-results] 통과. 풀 재사용·모든 RLS 쓰기 조합·HTTP 연결은 별도 |
| 스키마·권한 마이그레이션 | 부분 | 부분: 신규 설치 SQL만 | 부분: [임시 DB 신규 설치][pg-run] 통과. 기존 개발 DB 적용·데이터 보존 이행은 미수행 |

### 2.5 BFF · 인증/인가

설계: [API 계약][api]·[세션 ADR][session-adr]·[RBAC ADR][rbac-adr]. [현재 앱][bff]은 시작점만 있으며 아래 기능은 없다.

| 작업 | 설계 | 구현 | 검증·근거 |
|---|---|---|---|
| 로그인·로그아웃·본인 정보 | 부분 | 없음 | 없음: API 선언·세션 DB 검사만 있음 |
| 현재 소속·역할 → 두 DB 조회 범위 | 부분 | 없음 | 없음: 저장소 개별 검사를 실제 요청의 권한 전달로 볼 수 없음 |
| 구성원 역할 관리·감사 | 부분 | 없음 | 없음: DB 제약은 있으나 HTTP 권한 확인·감사 처리 없음 |
| 비용·정산·사용량 API | 부분 | 없음 | 없음: [계약·예제 32건][contract-results]만 통과. 실제 응답·커서·계산은 미검증 |
| 응답·오류 조합 | 부분 | 없음 | 없음: 오류 선언 검사는 실제 내부 정보 비노출 검사가 아님 |

### 2.6 월간 정산 배치

설계: [배치 ADR][batch-adr]·[저장소 계약][storage]. [현재 앱][batch]은 시작점만 있다. 정산 테이블 존재는 배치 구현 완료가 아니다.

| 작업 | 설계 | 구현 | 검증·근거 |
|---|---|---|---|
| 회사·월 시작·재시도 | 부분 | 없음 | 없음: DB 실행 상태 제약만 검사함 |
| 적체·귀속 오류 등 준비 확인 | 부분 | 없음 | 없음: 적체 관측 도구는 있지만 배치 차단 연결은 없음. 회사 불명 오류의 차단 범위 미결 |
| 재계산·비교 | 부분 | 없음 | 없음: 계산 SQL·시간 경계·가격 검증 연결이 남음 |
| 원자적 확정 | 부분 | 없음 | 없음: [DB 확정 SQL][pg-test]는 통과했으나 실제 배치 중단·재시도는 미검증 |

### 2.7 웹 대시보드

설계 근거: [요구사항][requirements]·[API 계약][api]. 프런트엔드 소스·실행 모듈 없음.

| 작업 | 설계 | 구현 | 검증·근거 |
|---|---|---|---|
| 예상 비용·필터·상세 화면 | 요구 | 없음 | 없음 |
| 월간 확정·미확정 표시 | 요구 | 없음 | 없음 |
| 로그인·역할·오류 화면 | 요구 | 없음 | 없음 |
| 구성원 역할 변경 화면 | 요구 | 없음 | 없음 |

### 2.8 실행 위치 미정인 책임

설계: [점유 복구 ADR][occupancy-adr]·[저장소 계약][storage]·[논리 모델][logical]. 점유 이력·사용량 귀속이 모듈 후보이며, [공개 안전성 계약](occupancy-attribution-contract.md)의 의미를 결정했다. 정확한 입력 스키마·실행 위치·원자적 처리의 세부 설계는 남았다. 실제 VM 제어는 외부 범위다.

| 작업 | 설계 | 구현 | 검증·근거 |
|---|---|---|---|
| 외부 점유 사실 수신 계약 | 부분 | 없음 | 없음: 완료 확인·연속 번호 의미 결정, 정확한 필드·발행 번호 보존 미설계 |
| 시작·종료 이력 반영 | 부분 | 없음 | 없음: 수신 후 이력 반영·실제 시각 보존 미구현 |
| 제어 사건 멱등 반영·버전 비교 | 부분 | 없음 | 없음 |
| 변경 중 공개 보류·재시작 복구 | 부분 | 없음 | 없음: 확인 범위 밖은 공개 보류로 결정. 공개 경합·복구 구현 미설계 |
| 사용 구간·점유 이력 매핑 | 부분 | 없음 | 없음: 월·조회·가격 경계 정책도 미결 |
| 귀속 오류·알림·차단·재처리 | 부분 | 없음 | 없음: 오류 저장·해결 이력과 회사 불명 차단 범위 미결 |
| 확정 월의 지연 이벤트 격리 | 부분 | 없음 | 없음: [정책][batch-adr]만 있음 |
| PostgreSQL → ClickHouse 가격 동기화 | 부분 | 없음 | 없음: 양쪽 스키마만 존재. 전송·검증·공개 명령 없음 |

## 3. 연결 검증과 남은 판단

| 범위 | 확인된 상태 |
|---|---|
| 발생기 → Kafka → ClickHouse | 실제 연결·재발행·저장 실패·재시작·접근 거부 CI 통과. [보장하지 않는 장애][ingestion]는 유지 |
| PostgreSQL ↔ 귀속 처리 ↔ ClickHouse | 미구현·미검증 |
| 웹 → BFF → 두 DB | 미구현·미검증. API 예제 통과와 다름 |
| 배치 → 두 DB | 미구현·미검증. 수동 SQL 제약 검사와 다름 |
| 성능·운영 | 동시 조회·배치 부하, 보관 만료, 운영 TLS·비밀 관리·외부 알림 미검증/미연결 |

따라서 수집의 ACK·중복 제거를 재설계할 필요는 없다. 공개 안전성의 기획 계약과 [테스트 설계](architecture-test-plan.md)는 마련했으며 미구현이다. 다음 판단은 **점유 이력·귀속 모듈의 실행 위치와 최소 저장 구조**다. 이후 정확한 입력 스키마·동일 버전 읽기·공개 경합·복구를 LLD로 구체화한다. 문서 결정이 새 기능 구현 승인을 뜻하지 않는다.

[event]: event-contract.md
[ingestion]: ingestion-implementation.md
[streaming]: adr/0002-event-streaming-for-realtime-aggregation.md
[occupancy-adr]: adr/0009-occupancy-event-recovery-and-cache-consistency.md
[ch-model]: clickhouse-physical-data-model.md
[storage]: storage-access-contract.md
[api]: api-contract.md
[pg-model]: postgresql-physical-data-model.md
[logical]: logical-data-model.md
[requirements]: requirements.md
[session-adr]: adr/0005-authentication-state-management.md
[rbac-adr]: adr/0006-tenant-rbac-enforcement.md
[batch-adr]: adr/0004-monthly-validation-and-finalization-batch.md
[generator]: ../apps/usage-generator/src/main/java/io/github/bbororo5/cloudbilling/generator/UsageGenerator.java
[publisher]: ../apps/usage-generator/src/main/java/io/github/bbororo5/cloudbilling/generator/AckPublisher.java
[generator-test]: ../apps/usage-generator/src/test/java/io/github/bbororo5/cloudbilling/generator/UsageGeneratorTest.java
[kafka-init]: ../scripts/init-ingestion-kafka.sh
[startup]: ../scripts/start-ingestion.sh
[kafka-input]: ../database/clickhouse/ingestion.sql
[kafka-config]: ../config/local/clickhouse-kafka.xml
[integration]: ../scripts/verify-ingestion.sh
[ch-schema]: ../database/clickhouse/schema.sql
[ch-test]: ../database/clickhouse/schema_test.sql
[ch-access]: ../database/clickhouse/local-access.sql
[queries]: ../database/clickhouse/queries/README.md
[pg-schema]: ../database/postgresql/schema.sql
[pg-roles]: ../database/postgresql/local-roles.sql
[guards]: ../database/postgresql/guard-privileges.sql
[pg-sql-test]: ../database/postgresql/schema_test.sql
[pg-test]: ../tests/postgres-access/src/accessTest/java/io/github/bbororo5/cloudbilling/access/PostgresAccessTest.java
[pg-results]: ../tests/postgres-access/README.md
[pg-run]: ../scripts/verify-postgresql-access.sh
[contract-results]: ../tests/contracts/README.md
[bff]: ../apps/billing-bff/src/main/java/io/github/bbororo5/cloudbilling/bff/BillingBffApplication.java
[batch]: ../apps/settlement-batch/src/main/java/io/github/bbororo5/cloudbilling/settlement/SettlementBatchApplication.java
