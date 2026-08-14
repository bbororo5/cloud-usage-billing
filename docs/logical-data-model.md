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

## 5. 다음 영역

수신 이벤트 로그와 유효 사용량 원장의 관계를 같은 절차로 모델링한다.
