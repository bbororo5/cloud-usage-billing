# 작업별 설계·구현·검증 상태

## 1. 판정 기준

기존 수집 기준은 `c512847`이며 당시 CI 성공을 확인했다. **2026-09-26 점유 이력 구현 후** 계약·단위·DB·실제 Kafka/워커 종료·인증된 로컬 스택 및 기존 PostgreSQL·ClickHouse·수집 회귀 검사를 로컬에서 재실행해 통과했다. 새 GitHub CI 실행 결과와는 구분한다.

같은 날 귀속 모듈 추가 후 `check`, 귀속 PG/CH 21건, 점유 DB/Kafka 복구, PostgreSQL 접근, ClickHouse 저장, 기존 수집 장애 회귀를 다시 통과했다. [귀속 구현 범위](usage-attribution-implementation.md)는 내부 승인까지이며 고객 공개를 포함하지 않는다.

2026-09-27 회고 보완: 승인 책임 단일화·대기/오류 타입 유지·잠금 계약 명확화·준비 내용 대조를 적용했다. `check`와 귀속 PG/CH 24건이 로컬 통과했다. 이번 커밋의 작성자·커미터 날짜는 사용자 요청으로 2026-09-26이며 실제 검증일과 구분한다.

2026-09-27 3번 인수: 실제 워커의 자동 발견·두 VM 오류 격리·중복·재시작을 추가해 귀속 25건 통과. `check`, 점유 DB/Kafka 복구, PostgreSQL 접근, ClickHouse 저장 회귀도 통과했다. 수집 회귀는 첫 실행의 장애 주입 오류 관측이 타임아웃됐으나 재실행 전체 통과했다. 원인은 미확정이며 타임아웃 시 실제 관측값을 보존하도록 진단을 보완했다. 고객 조회·부하·운영 검증은 포함하지 않는다.

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
| 점유 사실 토픽 | 완료 | 완료: 별도 토픽·신원·ACL·로컬 Compose | 통과: 인증된 3브로커 연결·소비자 쓰기 거부. 운영 TLS·외부 발생기는 별도 |

### 2.3 ClickHouse

설계: [물리 모델][ch-model]·[저장소 계약][storage]. 귀속 저장·내부 승인과 고객 공개·정정 완료를 구분한다.

| 작업 | 설계 | 구현 | 검증·근거 |
|---|---|---|---|
| 이벤트 적재·commit 연결 | 완료 | [완료][kafka-input] | 부분: [저장 실패·프로세스 재시작][integration] 통과. 전원 장애·fsync 실패 주입 없음 |
| 일반 뷰 중복 제거 | 완료 | [완료][ch-schema] | 통과: [다른 묶음·역순·source 분리·세 측정값 보존][ch-test]·[재전달][integration] |
| 귀속 결과 저장·정정 | 부분 | 부분: 불변 개정·중복 제거·내부 승인 | [귀속 검증](usage-attribution-implementation.md) 통과. 고객 공개·과거 정정은 미구현 |
| 테넌트별 신원·Row Policy | 부분 | [부분][ch-access] | 부분: [BFF 원장·뷰 접근 거부][integration]만 통과. 회사별 허용·차단은 없음 |
| 가격 사본 적재·버전 확인 | 부분 | [부분][ch-schema] | 부분: [최신 버전 선택][ch-test]만 통과. 동기화·누락·버전 대조는 없음 |
| 기간·서비스·자원별 집계 SQL | 부분 | 없음 | 없음: 귀속 모델·시간 경계 미결, [조회 SQL 미작성][queries] |
| 귀속 완료 사용량 커서 조회 | 부분 | 없음 | 없음: [커서 계약][api]만 있음 |

### 2.4 PostgreSQL

설계: [물리 모델][pg-model]. 저장소 제약과 이를 호출하는 애플리케이션 기능을 구분한다.

| 작업 | 설계 | 구현 | 검증·근거 |
|---|---|---|---|
| 사용자·소속·역할 제약 | 완료 | [완료][pg-schema]·[함수 권한][guards] | 부분: [마지막 Admin 보호·교차 회사 쓰기 거부][pg-test] 통과. 동시 Admin 변경 경합은 미검증 |
| 점유 이력·버전 제약 | 완료 | 완료: `occupancy.sql` | 통과: PK/FK·중첩 금지·원자적 반영·워커 최소 권한 |
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
| 적체·귀속 오류 등 준비 확인 | 부분 | 없음 | 없음: 적체 관측 도구는 있지만 배치 차단 연결은 없음. 국소 보류·운영 알림 연결 미구현 |
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

### 2.8 점유·귀속 워커

[점유 이력 LLD](occupancy-history-lld.md)의 수신함·VM별 트랜잭션·버전 캐시를 구현했다. [워커 README](../apps/occupancy-worker/README.md)에 실행·검증 경계를 기록했다. C4 표현은 에이전트 책임이며 외부 발행·귀속 공개 등의 후속 과제를 일괄 완료로 올리지 않는다.

2026-09-26 승인 계획에 따라 같은 컨테이너에 [사용량 귀속 모듈](usage-attribution-implementation.md)을 추가했다. 준비·내부 승인·복구까지 로컬 검증했고 고객 공개는 제외했다.

설계: [점유 복구 ADR][occupancy-adr]·[저장소 계약][storage]·[논리 모델][logical]. 귀속은 저장 검증 후 VM 잠금 아래 내부 승인한다. 고객 공개·과거 정정 연결은 후속 설계 대상이며 실제 VM 제어는 외부 범위다.

| 작업 | 설계 | 구현 | 검증·근거 |
|---|---|---|---|
| 외부 점유 사실 수신 계약 | 완료 | 완료: 스키마·수신·원문 보관 | 통과: 네 사건·오류 입력·중복·Kafka 재전달. 실제 발행자 번호 보존은 별도 |
| 시작·종료 이력 반영 | 완료 | 완료 | 통과: 실제 시각·구간 제약·누락/다른 VM 독립 진행·DB rollback |
| 제어 사건 멱등 반영·버전 비교 | 완료 | 완료 | 통과: 중복·내용 충돌·동시 반영·버전 캐시·스냅샷 경합 |
| 변경 중 공개 보류·재시작 복구 | 부분 | 부분: 내부 승인·조회·프로세스 복구 | 버전 경합·조회 잠금·후속 이력 오류·CH 저장 후 JVM 종료/복구 통과. 고객 공개는 별도 |
| 사용 구간·점유 이력 매핑 | 완료 | 완료: 이벤트 전체를 당시 점유 하나에 귀속 | 과거 회사·구간 경계·유휴·확인 부족·다중 점유 검사 통과. 월/가격 배분은 제외 |
| 귀속 오류·알림·차단·재처리 | 부분 | 부분: 영속 오류·감사·운영 재시도·알림 포트 | 권한·중복 요청·예약 후 보류 유지·회사 등록 후 복구·알림 실패 통과. 외부 인증/채널은 미연결 |
| 확정 월의 지연 이벤트 격리 | 부분 | 부분: 이미 확정된 UTC 월의 신규 승인 보류 | 확정 월 검사 통과. 배치와 동시 마감하는 전체 연결은 미검증 |

### 2.9 실행 위치 미정 — 가격 동기화

| 작업 | 설계 | 구현 | 검증·근거 |
|---|---|---|---|
| PostgreSQL → ClickHouse 가격 동기화 | 부분 | 없음 | 없음: 양쪽 스키마만 존재. 전송·검증·공개 명령 없음 |

## 3. 연결 검증과 남은 판단

BFF 설계 진행: [LLD](bff-access-lld.md)의 ① 책임·경계, ② 계약, [③ 오라클 설계](bff-access-test-design.md)를 마쳤다. 2026-10-01 승인한 G1–G6를 검증 목적 36개와 API 계약에 반영했다. 다음은 ④ 레이어·구성요소·객체 관계이며 공개 보호의 구현 수단은 여기서 검증한다. 실제 BFF 보안·경합·DB 연결 테스트는 아직 구현하지 않았다.

같은 날 `./gradlew check --no-daemon`과 계약 검사(이벤트 78건·점유 1건·API 37건)가 통과했다. 새 API 검사에서 로그인 오류 선언·보호 API의 저장소 장애 응답·공통 오류 예제 누락을 각각 실패로 확인한 뒤 수정했다. 작성자·커미터 날짜는 사용자 요청으로 2026-09-30이며 실제 검증일과 구분한다. 선언·예제 통과는 아래 고객 경로의 구현·검증 상태를 바꾸지 않는다.

| 범위 | 확인된 상태 |
|---|---|
| 발생기 → Kafka → ClickHouse | 실제 연결·재발행·저장 실패·재시작·접근 거부 CI 통과. [보장하지 않는 장애][ingestion]는 유지 |
| Kafka → 점유 수신함 → 내부 이력 조회 | 실제 DB·브로커·별도 JVM 종료/재시작·인증 스택 로컬 검증 통과 |
| PostgreSQL ↔ 귀속 처리 ↔ ClickHouse | 3번 내부 처리 인수 완료. 실제 워커 자동 발견·두 VM 오류 격리·중복·재시작을 포함해 로컬 통합 25건 통과. 고객 공개 아님 |
| 웹 → BFF → 두 DB | 미구현·미검증. API 예제 통과와 다름 |
| 배치 → 두 DB | 미구현·미검증. 수동 SQL 제약 검사와 다름 |
| 성능·운영 | 동시 조회·배치 부하, 보관 만료, 운영 TLS·비밀 관리·외부 알림 미검증/미연결 |

다음은 고객 조회 경계(BFF 인증/인가 → PG 승인/차단 → CH 테넌트 격리)의 LLD와 연결 검증이다. 귀속 준비·내부 승인 구현을 반복하지 않는다. 가격·시간 경계 정책을 확정하기 전 비용 API 구현으로 넘어가지 않는다.

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
