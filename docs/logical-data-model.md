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

## 4. 다음 영역

`AuthenticationSession`과 보안 감사 이력을 같은 절차로 사용자·소속 모델에 연결한다.
