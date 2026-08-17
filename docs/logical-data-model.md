# Logical Data Model

## 1. 목적

요구사항을 데이터의 사실·관계·규칙으로 변환하고, 물리 저장 방식과 분리해 설계 근거를 남긴다.

## 2. 모델링 절차

```text
사용 사례 → 저장할 사실 → 소유 책임 → 엔티티·식별자
→ 관계·카디널리티 → 생명주기 → 불변 규칙
→ 읽기·쓰기 경로 → 품질 시나리오 검증
```

각 영역은 이 순서로 검토한다. 테이블·컬럼 타입·인덱스·HTTP 상태는 물리 모델과 API 계약에서 다룬다.

## 3. 사용자·회사·소속

### 근거가 된 사용 사례

- 등록된 사용자가 로그인해 자신의 회사와 역할을 확인한다. (`FR-10`)
- Viewer와 Admin은 소속 회사의 비용만 조회한다. (`FR-06`, `FR-08`)
- Admin은 소속 회사 구성원의 역할을 관리한다. (`FR-07`, `FR-12`)

### 저장해야 하는 사실

| 사실 | 엔티티 |
|---|---|
| 로그인 가능한 사용자가 존재한다. | `User` |
| 비용 데이터가 격리되는 회사가 존재한다. | `BillingAccount` |
| 사용자가 회사에서 역할을 가진다. | `BillingMembership` |

`BillingAccount`는 회사·테넌트이며 FOCUS의 `BillingAccountId`와 같은 식별 범위다.

### 엔티티 책임

| 엔티티 | 책임 | 논리 식별자 |
|---|---|---|
| `User` | 사용자 신원과 로그인 식별 | `UserId` |
| `BillingAccount` | 회사와 비용 격리 범위 | `BillingAccountId` |
| `BillingMembership` | 사용자–회사 소속과 역할 | `BillingAccountId + UserId` |

### 관계와 카디널리티

```text
BillingAccount 1 ── N BillingMembership
User           1 ── 0..1 BillingMembership
```

- 한 회사에는 여러 사용자가 속할 수 있다.
- 현재 범위에서 사용자는 활성 회사 소속을 최대 하나만 가진다.
- 회사 전환 기능이 추가될 때만 사용자 다중 소속을 다시 검토한다.

### 상태와 생명주기

- `User`는 소속과 독립적으로 존재한다.
- `BillingMembership`은 `BILLING_ACCOUNT_VIEWER` 또는 `BILLING_ACCOUNT_ADMIN` 역할 하나를 가진다.
- 역할 제거는 사용자를 삭제하지 않고 소속을 종료한다.
- 사용자 가입·초대와 회사 삭제는 현재 범위에서 다루지 않는다.

### 불변 규칙

- 역할은 `User`가 아니라 `BillingMembership`에 귀속된다.
- 같은 사용자–회사 소속은 중복될 수 없다.
- 한 사용자는 활성 소속을 두 개 이상 가질 수 없다.
- 활성 회사에는 Admin이 한 명 이상 존재해야 한다.

### 선택 근거

| 선택지 | 판단 |
|---|---|
| 역할을 `User`에 저장 | 회사 범위가 사라져 멀티테넌트 권한을 표현하지 못한다. |
| 역할을 `BillingMembership`에 저장 | 역할의 회사 범위와 소속 생명주기가 명확하다. |

### 주요 읽기·쓰기 경로

| 동작 | 읽기 | 변경 |
|---|---|---|
| 로그인·내 정보 확인 | `User`, `BillingMembership`, `BillingAccount` | 없음 |
| 비용 조회 권한 확인 | `BillingMembership` | 없음 |
| 구성원·역할 조회 | `BillingMembership`, `User` | 없음 |
| 역할 부여·변경·제거 | `BillingMembership` | `BillingMembership` |

### 검증 시나리오

- 다른 회사의 `UserId`는 현재 `BillingAccountId`의 소속으로 조회되지 않는다.
- 동일 사용자 소속을 중복 생성할 수 없다.
- 마지막 Admin의 소속이나 역할을 제거할 수 없다.
- 역할을 제거해도 `User` 신원은 유지된다.

### 물리 모델로 넘길 사항

- 식별자 타입과 물리 기본키
- 활성 소속의 표현과 유일성 제약
- 마지막 Admin 규칙의 트랜잭션 처리
- PostgreSQL RLS와 인덱스

## 4. 인증 세션·보안 감사

### 근거가 된 사용 사례

- 로그인 상태를 유지하고 로그아웃한다. (`FR-10`)
- 역할 변경을 기존 인증 상태에 즉시 반영한다. (`QS-11`)
- 역할 변경과 접근 거부를 추적한다. (`FR-12`)

### 저장해야 하는 사실

| 사실 | 엔티티 |
|---|---|
| 사용자의 로그인 상태가 유효 기간 동안 존재한다. | `AuthenticationSession` |
| 역할이 변경됐다. | `RoleChangeAudit` |
| 권한 부족으로 접근이 거부됐다. | `AccessDenialAudit` |

두 감사 엔티티는 공통 식별·회사·행위자·발생 시각을 갖는 `SecurityAuditEvent` 유형이다.

### 엔티티 책임

| 엔티티 | 책임 | 논리 식별자 |
|---|---|---|
| `AuthenticationSession` | 사용자 인증 상태와 만료·폐기 | `SessionId` |
| `SecurityAuditEvent` | 보안 판단의 변경 불가능한 증거 | `AuditEventId` |
| `RoleChangeAudit` | 대상 사용자의 역할 전후 기록 | `AuditEventId` |
| `AccessDenialAudit` | 거부된 행위와 판단 사유 기록 | `AuditEventId` |

### 관계와 카디널리티

```text
User           1 ── N AuthenticationSession
BillingAccount 1 ── N SecurityAuditEvent
User           1 ── N SecurityAuditEvent : actor
User           1 ── 0..N RoleChangeAudit  : target
```

세션은 `User`만 참조한다. 회사와 역할은 요청 시 현재 `BillingMembership`에서 확인한다.

### 상태와 생명주기

- 세션은 로그인 성공으로 생성되고 만료·로그아웃·계정 차단·역할 변경으로 효력을 잃는다.
- 한 사용자는 브라우저·기기별로 여러 활성 세션을 가질 수 있다.
- 감사 이벤트는 보안 판단과 함께 한 번 생성되며 이후 변경하지 않는다.
- 사용자나 소속의 현재 상태가 바뀌어도 당시 식별자와 판단은 유지한다.

### 불변 규칙

- 세션에는 회사와 역할을 권한의 정답으로 저장하지 않는다.
- 활성 세션만 인증에 사용할 수 있다.
- 역할 변경 감사에는 회사·행위자·대상·변경 전후 역할이 존재한다.
- 접근 거부 감사에는 회사·행위자·시도한 행위·거부 사유가 존재한다.
- 감사 이벤트는 현재 권한을 복원하는 원본으로 사용하지 않는다.
- 감사 이벤트에 세션 ID·비밀번호·자격 증명을 기록하지 않는다.

### 선택 근거

| 선택지 | 판단 |
|---|---|
| 세션이 소속과 역할을 소유 | 변경 전 권한이 세션에 남을 수 있다. |
| 세션은 사용자만 식별 | 현재 소속·역할을 매 요청에서 확인할 수 있다. |
| 모든 보안 이력을 하나의 자유 형식 데이터로 저장 | 필수 증거가 이벤트마다 달라 누락을 막기 어렵다. |
| 역할 변경과 접근 거부 유형을 구분 | 공통 증거를 유지하면서 유형별 필수 사실을 명시할 수 있다. |

### 주요 읽기·쓰기 경로

| 동작 | 읽기 | 변경 |
|---|---|---|
| 로그인 | `User` | `AuthenticationSession` 생성 |
| 사용자 API 인증 | `AuthenticationSession`, `BillingMembership` | 세션 접근 시각 |
| 로그아웃 | `AuthenticationSession` | 세션 폐기 |
| 역할 변경 | `BillingMembership`, `AuthenticationSession` | 역할·세션, `RoleChangeAudit` 추가 |
| 권한 부족 요청 | `BillingMembership` | `AccessDenialAudit` 추가 |

### 검증 시나리오

- 역할을 제거한 뒤 기존 세션으로 관리 API를 호출해도 허용되지 않는다.
- Viewer의 역할 변경 요청은 상태를 바꾸지 않고 거부 이력을 남긴다.
- 사용자의 세션을 하나 폐기해도 다른 사용자 세션은 영향을 받지 않는다.
- 감사 이력만 변경해도 현재 역할과 권한은 바뀌지 않는다.

### 물리 모델로 넘길 사항

- Spring Session 테이블과 사용자 식별 연결 방식
- 역할 변경과 관련 세션 폐기의 트랜잭션 경계
- 감사 유형을 단일 테이블 또는 분리 테이블로 구현하는 방식
- 감사 보관 기간과 조회 인덱스

## 5. 수신 이벤트·사용량 원장

### 근거가 된 사용 사례

- 1분 사용량 이벤트를 검증하고 유효·거부로 구분한다. (`FR-01`, `FR-02`)
- 동일 이벤트를 여러 번 받아도 한 번만 비용에 반영한다. (`QS-01`)
- 지연·순서 변경 이벤트를 사용 시각에 반영한다. (`QS-02`)
- 원본 사용량과 계산 근거를 조회한다. (`FR-13`)

### 저장해야 하는 사실

| 사실 | 엔티티 |
|---|---|
| 사용량을 발행할 수 있는 시스템 신원이 존재한다. | `UsageProducer` |
| 발생기 자격 증명의 유효 상태를 관리한다. | `ProducerCredential` |
| 특정 출처에서 사용량 이벤트가 발생했다. | `UsageEvent` |
| 이벤트에 세 종류의 원시 측정 사용량이 포함됐다. | `RawUsageRecord` |
| 이벤트가 계약을 위반해 거부됐다. | `EventRejection` |
| 회사가 VM을 점유한 유효 구간 이력을 관리한다. | `ResourceAllocation` |
| 점유 이력으로 사용량이 특정 회사에 귀속됐다. | `AttributedUsageRecord` |
| 점유 이력을 찾지 못하거나 중복 점유되어 귀속에 실패했다. | `AttributionError` |

Kafka의 재전송 사본은 새로운 `UsageEvent`가 아니다. 같은 `source + id`는 동일한 논리 이벤트다.

### 엔티티 책임

| 엔티티 | 책임 | 논리 식별자 |
|---|---|---|
| `UsageProducer` | 허용된 이벤트 발생기 신원과 CloudEvents 출처 | `ProducerId` |
| `ProducerCredential` | 발생기 비밀의 해시·유효 기간·폐기 상태 | `CredentialId` |
| `UsageEvent` | CloudEvents 식별·출처·발생 시각과 측정 묶음 | `source + id` |
| `RawUsageRecord` | meter별 원시 측정량과 사용 구간 (테넌트 비인지) | `source + id + meter` |
| `EventRejection` | 거부 시점·단계·사유와 식별 가능한 요청 정보 | `RejectionId` |
| `ResourceAllocation` | 회사가 VM을 점유한 유효 구간 | `BillingAccountId + source + ValidFrom` |
| `AttributedUsageRecord` | 회사에 귀속 완료된 사용량 레코드 (조회 모델) | `BillingAccountId + source + id + meter` |
| `AttributionError` | 미귀속·중복 귀속으로 격리된 사용량 오류 | `ErrorId` |

`RawUsageRecord`는 VM의 원시 meter·수량·단위를 보존한다. FOCUS 의미와 회사 식별자는 점유 이력(`ResourceAllocation`) 및 가격 결합 이후의 `AttributedUsageRecord`와 비용 모델에 적용한다.

### 관계와 카디널리티

```text
UsageProducer  1 ── N ProducerCredential
UsageProducer  1 ── N UsageEvent
UsageEvent      1 ── 3 RawUsageRecord

BillingAccount 1 ── N ResourceAllocation N ── 1 UsageProducer
RawUsageRecord + ResourceAllocation ── 1 AttributedUsageRecord (귀속 성공 시)
                                    └── 1 AttributionError     (귀속 실패 시)
```

- 하나의 발생기는 회전을 위해 여러 자격 증명 이력을 가질 수 있다.
- 한 이벤트는 Compute·Storage·Networking 원시 레코드를 각각 하나씩 가진다.
- 회사는 `[validFrom, validTo)` 동안 VM을 점유하며 종료된 이력을 삭제하지 않는다.
- Kafka에는 같은 논리 이벤트의 전달 사본이 하나 이상 존재할 수 있다.
- 유효 이벤트만 Kafka 수신 로그와 ClickHouse 원시 원장으로 전달된다.
- 거부 기록은 유효 이벤트·원시 사용량 레코드를 만들지 않는다.
- 점유 구간과 일치하지 않는 원시 레코드는 `AttributionError`로 격리되며 조회·정산에서 차단된다.

### 상태와 생명주기

- 발생기 자격 증명은 발급 후 활성화되고 만료·회전·폐기로 효력을 잃는다.
- 요청은 계약 검증 후 거부되거나 Kafka에 내구성 있게 기록된다.
- Kafka 소비자는 이벤트를 세 원시 사용량 레코드로 풀어 ClickHouse 원시 원장에 추가한다.
- 유효 이벤트와 원시 사용량 레코드는 수정하지 않는다.
- 지연 이벤트도 열린 월의 원장에 원래 사용 구간으로 추가한다.
- Kafka 보관 종료 후에도 ClickHouse 원시 원장은 유지된다.
- 점유 이력으로 귀속된 `AttributedUsageRecord`가 생성/갱신되며, BFF는 이 귀속 모델만 조회한다.

### 불변 규칙

- 인증된 발생기의 `ProducerId`와 CloudEvents `source`가 일치해야 한다.
- 활성 상태와 유효 기간을 만족하는 발생기 자격 증명만 사용할 수 있다.
- 발생기 비밀 원문은 저장하지 않는다.
- `source + id`가 같으면 재전송된 동일 이벤트다.
- 세 레코드의 `ResourceId`와 사용 구간은 같고 회사·가격 정보는 포함하지 않는다.
- 사용 구간은 시작 포함·종료 제외이며 일반 구간은 60초다.
- 같은 리소스의 서로 다른 논리 이벤트는 사용 구간이 겹치지 않는다.
- 각 사용량은 0 이상의 정수이며 계약에 정의된 단위를 사용한다.
- 물리적으로 중복 적재돼도 하나의 논리 `RawUsageRecord`로만 계산한다.
- 미귀속(`AttributionError`) 사용량이 존재하는 월은 월간 확정을 수행할 수 없다.

### 선택 근거

| 선택지 | 판단 |
|---|---|
| 수신 시점에 회사·SKU 보강 | 점유 매핑 장애가 수집을 막고 과거 점유 변경을 유연하게 처리하지 못한다. |
| 원시 사용량과 후행 귀속 분리 | VM 계약이 단순해지고 수집 내구성과 점유 이력 정확성을 동시에 달성한다. |
| ClickHouse에 CloudEvent 묶음만 저장 | 서비스·리소스 필터마다 배열을 해체해야 한다. |
| 사용량 레코드로 풀어 저장 | 필드 단위 조회와 집계가 직접 가능하다. |
| `source + id`를 논리 이벤트로 취급 | At-least-once 전달과 비용 멱등성을 함께 유지한다. |

### 주요 읽기·쓰기 경로

| 동작 | 읽기 | 변경 |
|---|---|---|
| 발생기 인증 | `UsageProducer`, `ProducerCredential` | 자격 증명 사용 기록 |
| 이벤트 검증 | 인증된 발생기, 요청 CloudEvent | `EventRejection` 또는 Kafka 로그 |
| 원장 적재 | Kafka의 `UsageEvent` | ClickHouse `RawUsageRecord` 추가 |
| 사용량 귀속 | `RawUsageRecord`, `ResourceAllocation` | `AttributedUsageRecord` 생성 또는 `AttributionError` 격리 |
| 예상 비용 조회 | `AttributedUsageRecord`, 가격 사본 | 없음 (BFF는 원시 원장 직접 접근 불가) |
| 원본 상세 조회 | `AttributedUsageRecord` | 없음 |
| 월간 검증 | 해당 월의 `AttributedUsageRecord`, `AttributionError` | 없음 |

### 검증 시나리오

- 폐기된 발생기 자격 증명으로 보낸 이벤트는 Kafka에 기록되지 않는다.
- 인증된 발생기와 다른 `source`를 선언한 이벤트는 거부된다.
- 같은 `source + id`를 반복 전달해도 사용량 합계가 변하지 않는다.
- 순서가 바뀌어 도착해도 `ChargePeriodStart/End` 기준 결과가 같다.
- 세 레코드 중 하나가 없거나 단위가 틀린 이벤트는 원장에 들어가지 않는다.
- 사용 구간과 겹치는 점유 관계가 없는 레코드는 `AttributionError`로 격리되어 월간 확정이 차단된다.
- Kafka 소비 중단 후 재개해도 정상 처리 결과와 같다.

### 물리 모델로 넘길 사항

- 발생기 비밀의 해시 방식과 발급·회전 절차
- 거부 이벤트의 저장 범위·저장소·보관 기간
- Kafka topic·보관 기간과 consumer group
- ClickHouse 엔진·파티션·정렬 키
- 물리 중복 제거 쿼리와 적재 버전
- 사용 구간 중복 검증 방식

## 6. 가격 정책·비용 계산

### 근거가 된 사용 사례

- 사용량을 정해진 단가로 계산해 예상 비용을 제공한다. (`FR-03`, `FR-04`)
- 같은 사용량과 가격으로 다시 계산하면 같은 결과를 만든다. (`QS-06`)
- 금액에서 사용량과 적용 단가를 추적한다. (`QS-08`)

### 저장해야 하는 사실

| 사실 | 엔티티 |
|---|---|
| 과금 가능한 SKU와 측정 단위가 정의돼 있다. | `PricingSku` |
| SKU의 단가가 특정 시점부터 적용된다. | `PriceRate` |
| 사용량과 적용 가격으로 비용이 계산된다. | `CalculatedCharge` |

`CalculatedCharge`는 조회나 배치에서 만들어지는 파생 결과이며 현재 예상 비용의 원본으로 저장하지 않는다.

### 엔티티 책임

| 엔티티 | 책임 | 논리 식별자 |
|---|---|---|
| `PricingSku` | 서비스·meter·사용량 단위의 과금 정의 | `SkuId` |
| `PriceRate` | SKU의 적용 시작 시각·단가·통화 | `PriceRateId` |
| `CalculatedCharge` | 귀속 사용량·가격 버전·계산 금액의 추적 가능한 결과 | `AttributedRecordId + PriceRateId` |

### 관계와 카디널리티

```text
PricingSku 1 ── N PriceRate
PricingSku 1 ── N AttributedUsageRecord

AttributedUsageRecord 1 ─┐
                         ├─ 1 CalculatedCharge
PriceRate             1 ─┘
```

- `AttributedUsageRecord`의 meter·단위와 일치하는 과금 SKU를 찾는다.
- 사용 구간을 완전히 포함하는 가격 버전 하나를 선택한다.
- `CalculatedCharge`는 귀속 사용량과 가격 버전 양쪽을 추적한다.

### 상태와 생명주기

- SKU는 구현 범위의 Compute·Storage·Networking 항목으로 등록한다.
- 새 단가는 기존 단가를 덮어쓰지 않고 새 `PriceRate`로 추가한다.
- 가격 버전의 적용 구간은 해당 시작 시각부터 다음 버전 시작 전까지다.
- PostgreSQL 가격이 정답이며 ClickHouse 가격 사본은 다시 만들 수 있다.
- 현재 예상 비용은 요청마다 계산하고 월간 확정 시점에만 결과를 저장한다.

### 불변 규칙

- 같은 SKU의 가격 적용 구간은 겹치지 않는다.
- 한 사용 구간에는 정확히 하나의 `PriceRate`가 적용돼야 한다.
- 가격 경계를 가로지르는 사용 구간은 경계에서 나눠야 한다.
- `AttributedUsageRecord.ConsumedUnit`은 `PricingSku`의 측정 단위와 같아야 한다.
- 단가는 0 이상이며 통화는 KRW다.
- 가격 사본이 없거나 버전이 모호하면 금액을 0으로 계산하지 않고 실패한다.
- 예상 조회와 월간 배치는 같은 계산 규칙을 사용한다.

### 선택 근거

| 선택지 | 판단 |
|---|---|
| 사용량 이벤트가 단가를 제공 | 외부 입력이 가격의 정답이 되고 가격 변경 이력을 통제할 수 없다. |
| 내부 가격 버전을 적용 | 가격 소유권과 과거 재계산의 결정성이 유지된다. |
| 현재 단가를 덮어쓰기 | 과거 사용량을 재계산할 때 당시 가격을 잃는다. |
| 새 가격 버전을 추가 | 사용 시점에 적용된 가격을 다시 선택할 수 있다. |

### 주요 읽기·쓰기 경로

| 동작 | 읽기 | 변경 |
|---|---|---|
| 가격 등록 | `PricingSku`, 기존 `PriceRate` | 새 `PriceRate` |
| 가격 사본 갱신 | PostgreSQL `PriceRate` | ClickHouse 가격 사본 |
| 예상 비용 조회 | `AttributedUsageRecord`, 가격 사본 | 파생 `CalculatedCharge` |
| 월간 검증 | `AttributedUsageRecord`, `PriceRate` | 파생 `CalculatedCharge` |

### 검증 시나리오

- 가격 변경 뒤 과거 사용량을 계산해도 당시 가격 버전이 선택된다.
- 같은 사용량과 가격 버전의 계산 결과는 실행마다 같다.
- 가격 적용 구간이 겹치거나 비어 있으면 계산을 중단한다.
- ClickHouse 가격 사본을 다시 만들어도 예상 비용이 달라지지 않는다.
- 계산 결과에서 `AttributedUsageRecord`와 `PriceRate`를 모두 찾을 수 있다.

### 물리 모델로 넘길 사항

- 가격 적용 구간의 PostgreSQL 제약
- PostgreSQL에서 ClickHouse로 가격을 전달하는 방식
- ClickHouse 가격 사본의 테이블 또는 Dictionary 선택
- 금액·단가의 Decimal 정밀도와 반올림 시점
- 가격 경계에서 사용량을 분할·검증하는 위치

## 7. 월간 검증·확정

### 근거가 된 사용 사례

- 월 사용량을 다시 계산해 예상 결과를 검증하고 확정한다. (`FR-05`)
- 실패한 배치를 안전하게 재실행한다. (`QS-05`)
- 배치 중에도 기존 확정 결과만 사용자에게 제공한다. (`QS-12`)

### 저장해야 하는 사실

| 사실 | 엔티티 |
|---|---|
| 회사의 한 달을 정산해야 한다. | `SettlementJob` |
| 정산 작업을 한 번 시도했다. | `SettlementAttempt` |
| 재계산 결과와 예상 결과를 비교했다. | `SettlementValidation` |
| 검증된 월간 금액이 확정됐다. | `MonthlySettlement` |

`BillingMonth`는 UTC 월 시작과 다음 달 시작으로 이루어진 값이며 모든 엔티티가 같은 기간을 사용한다.

### 엔티티 책임

| 엔티티 | 책임 | 논리 식별자 |
|---|---|---|
| `SettlementJob` | 회사·월 단위 정산 진행 상태 | `BillingAccountId + BillingMonth` |
| `SettlementAttempt` | 한 번의 실행 결과와 실패 정보 | `RunId` |
| `SettlementValidation` | 예상·재계산 금액과 차이·입력 기준 시각 | `RunId` |
| `MonthlySettlement` | 사용자에게 공개하는 유일한 확정 금액 | `BillingAccountId + BillingMonth` |

### 관계와 카디널리티

```text
BillingAccount  1 ── N SettlementJob
SettlementJob   1 ── N SettlementAttempt
SettlementAttempt 1 ── 0..1 SettlementValidation
SettlementJob   1 ── 0..1 MonthlySettlement
```

- 실패한 작업은 새 `SettlementAttempt`로 재시도한다.
- `MonthlySettlement`는 성공한 실행과 검증 결과를 참조한다.
- 사용량과 가격은 다른 저장소의 소유 데이터이며 실행이 읽기만 한다.

### 상태와 생명주기

- 유예 시간과 적체 확인을 통과하면 회사·월별 `SettlementJob`을 준비한다.
- 실행 시도는 `RUNNING`에서 `FAILED` 또는 `VALIDATED`로 끝난다.
- 검증 성공 후 PostgreSQL 트랜잭션에서 확정 결과를 만들고 작업을 `FINALIZED`로 바꾼다.
- 실패한 시도는 보존하며 확정되지 않은 작업만 재시도한다.
- 확정 결과는 생성 후 변경하거나 다시 정산하지 않는다.

### 불변 규칙

- 같은 회사·월의 정산 작업과 확정 결과는 각각 하나만 존재한다.
- 한 작업에는 동시에 실행 중인 시도가 하나만 존재한다.
- 검증 결과 없는 실행은 확정 결과를 만들 수 없다.
- 검증 차이가 0원이 아니면 확정할 수 없다.
- 해당 월에 미귀속(`AttributionError`) 사용량이 존재하면 확정할 수 없다.
- `MonthlySettlement.BilledCost`는 선택된 검증의 재계산 금액과 같다.
- 진행 중·실패·검증 전 결과는 사용자에게 공개하지 않는다.
- 한 회사의 실패가 다른 회사의 작업 상태를 바꾸지 않는다.

### 선택 근거

| 선택지 | 판단 |
|---|---|
| 재시도할 때 실행 행을 덮어쓰기 | 실패 횟수와 원인을 잃고 동시 실행을 구분하기 어렵다. |
| 작업과 실행 시도를 분리 | 회사·월의 단일 작업과 여러 재시도를 함께 표현한다. |
| ClickHouse에 확정 상태도 저장 | PostgreSQL과 원자적으로 확정하기 어렵다. |
| PostgreSQL에 확정 결과 저장 | 실행 상태와 단일 트랜잭션으로 확정할 수 있다. |

### 주요 읽기·쓰기 경로

| 동작 | 읽기 | 변경 |
|---|---|---|
| 작업 준비 | 적체 상태, `SettlementJob` | `SettlementJob` |
| 실행 시작 | `SettlementJob` | `SettlementAttempt` |
| 월간 재계산 | `AttributedUsageRecord`, `PriceRate` | 파생 `CalculatedCharge` |
| 검증 | 예상 금액, 재계산 금액, `AttributionError` 유무 | `SettlementValidation` |
| 확정 | 검증된 실행 | `MonthlySettlement`, `SettlementJob` |
| 월간 조회 | `MonthlySettlement` | 없음 |

### 검증 시나리오

- 실행 중단 후 새 시도로 재실행하면 정상 실행과 같은 금액이 확정된다.
- 같은 회사·월 작업이 중복 실행돼도 확정 결과는 하나뿐이다.
- 검증 차이가 있거나 미귀속 데이터가 있는 실행은 실패 상태를 남기고 공개되지 않는다.
- 배치 중 사용자는 이전 확정 결과와 현재 귀속 모델 기반 예상 비용을 계속 조회한다.
- 한 회사의 실패 작업만 재시도하고 다른 회사의 확정은 유지한다.

### 물리 모델로 넘길 사항

- PostgreSQL 상태 전이·유일성·동시 실행 제약
- 작업 선점과 재시도 스케줄링 방식
- 오류 상세와 실행 이력의 보관 기간
- 확정 트랜잭션과 장애 지점별 복구 쿼리
- 금액 Decimal 정밀도와 반올림 규칙

## 8. 전체 모델 검증

### 관계 지도

```text
User ── BillingMembership ── BillingAccount ── ResourceAllocation ── UsageProducer ── UsageEvent ── RawUsageRecord
  └──── AuthenticationSession        │                                                                     │
                                     ├─ SecurityAuditEvent                                                 │
                                     ├─ AttributedUsageRecord ─────────────────────────────────────────────┘
                                     │         └─ PricingSku ─ PriceRate
                                     │                  │
                                     │                  └─ CalculatedCharge (파생)
                                     └─ SettlementJob ── SettlementAttempt ── SettlementValidation
                                              └───────── MonthlySettlement

UsageProducer ── ProducerCredential
```

### 경계를 잇는 식별자

| 식별자 | 연결 범위 |
|---|---|
| `BillingAccountId` | 소속·VM 점유·귀속 조회·감사·정산의 동일 회사 범위 |
| `source` | VM 발생기·원시 사용량·점유 이력 연결 |
| `source + id` | Kafka 재전송과 ClickHouse 원시 원장 논리 이벤트 중복 판정 |
| `source + id + meter` | 이벤트 안의 meter별 원시 사용량 레코드 |
| `SkuId` | 귀속 사용량과 가격 정책 |
| `PriceRateId` | 계산 금액과 적용 가격 버전 |
| `BillingAccountId + BillingMonth` | 회사별 월간 작업과 확정 결과 |
| `RunId` | 재시도별 실행·검증·확정 근거 |

`tenantId`는 내부 구현에서 사용하는 이름이며 논리적으로 `BillingAccountId`와 같다. 별도 회사 식별자를 만들지 않는다.

### API와 모델 연결

| API | 사용하는 모델 |
|---|---|
| 세션·내 정보 | `User`, `AuthenticationSession`, `BillingMembership`, `BillingAccount` |
| 비용 조회 | `BillingMembership`, `AttributedUsageRecord`, `PricingSku`, `PriceRate`, 파생 `CalculatedCharge` |
| 월간 확정 조회 | `BillingMembership`, `MonthlySettlement` |
| 원본 사용량 조회 | `BillingMembership`, `AttributedUsageRecord` |
| 구성원·역할 관리 | `BillingMembership`, `User`, `SecurityAuditEvent` |
| 이벤트 수신 | `UsageProducer`, `ProducerCredential`, `UsageEvent`, `EventRejection` |

사용자 API는 `BillingAccountId`를 입력받지 않고 현재 세션의 소속에서 결정한다. BFF는 원시 사용량 원장(`RawUsageRecord`)에 접근하지 않으며 귀속 완료된 조회 모델(`AttributedUsageRecord`)만 읽는다.

### 파생 데이터의 의미

- 현재 예상 비용과 `CalculatedCharge`는 요청 시 계산하며 정답으로 저장하지 않는다.
- `dataAsOf`는 해당 조회에 포함된 중복 제거 사용량의 가장 늦은 `ChargePeriodEnd`다.
- 조회 조건에 맞는 사용량이 없으면 `dataAsOf`는 `null`이다.
- `dataAsOf`는 발생기가 보내지 않은 이벤트까지 완전하다는 보장이 아니다.
- 가격 사본·조회 캐시·선집계는 원본에서 다시 만들 수 있어야 한다.
- 확정 결과만 `MonthlySettlement`로 영속화한다.

### 품질 시나리오 확인

| 관점 | 모델의 방어 근거 |
|---|---|
| 중복·지연·재계산 | 논리 이벤트 식별자, 사용 구간, 가격 버전 |
| 배치 실패·재시도 | 단일 월간 작업, 복수 실행 시도, 단일 확정 결과 |
| 테넌트·역할 격리 | 소속별 역할과 사용 시점의 `ResourceAllocation`을 통한 후행 귀속, BFF 원시 접근 차단 |
| 권한 변경 즉시 반영 | 사용자만 식별하는 세션과 현재 소속 조회 |
| 추적 가능성 | 예상 금액은 `AttributedUsageRecord + PriceRate`, 확정 금액은 `MonthlySettlement + RunId`로 근거 추적 |
| 조회 성능 | 물리 모델의 파티션·정렬·쿼리로 검증 |

### 요구사항 추적

| 근거 | 모델 또는 후속 검증 |
|---|---|
| `FR-01`, `FR-02` | 발생기·이벤트·거부·원시 사용량 원장 |
| `FR-03`, `FR-04` | 귀속 사용량·가격 버전·파생 비용 |
| `FR-05` | 월간 작업·실행·검증·확정 (미귀속 격리 확인) |
| `FR-06`~`FR-08`, `FR-10`, `FR-12` | 사용자·소속·역할·세션·감사 |
| `FR-09` | VM source와 시간 구간을 가진 회사 점유 이력 및 후행 귀속 |
| `FR-11` | 영속 모델 추가 없이 API 계약을 사용하는 웹 화면 |
| `FR-13` | 서비스별 귀속 사용량 레코드와 조회 조건 |
| `QS-01`, `QS-02` | 논리 이벤트 식별과 사용 구간 |
| `QS-03` | Kafka 수신 로그와 물리 처리량 검증 |
| `QS-04`, `QS-13` | ClickHouse 물리 모델과 부하 검증 |
| `QS-05`, `QS-06` | 실행 시도·단일 확정·가격 버전 |
| `QS-07` | 점유 이력 기반 귀속 모델과 BFF 원시 접근 차단 |
| `QS-08`, `QS-09` | 계산 연결과 `dataAsOf` 의미 |
| `QS-10`, `QS-11` | 현재 역할 조회·세션 폐기·감사 |
| `QS-12` | 진행 결과 비공개와 배치·조회 부하 격리 검증 |

### 물리 저장소로 전달

| 저장소 | 논리 모델 |
|---|---|
| PostgreSQL | 사용자·소속·세션·감사·VM 점유 이력(`ResourceAllocation`)·발생기 자격 증명·가격·배치·확정·미귀속 오류(`AttributionError`) |
| Kafka | 검증된 `UsageEvent` 수신 로그 |
| ClickHouse | 원시 원장(`RawUsageRecord`), 귀속 조회 모델(`AttributedUsageRecord`), 재생성 가능한 가격 사본 |

논리 모델에는 정답 소유자가 둘인 데이터가 없다. 다음 단계에서는 이 모델을 PostgreSQL과 ClickHouse의 키·제약·파티션·정렬 구조로 변환한다.
