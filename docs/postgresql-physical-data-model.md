# PostgreSQL Physical Data Model

## 1. 목적

논리 모델의 인증·소속·가격·정산 규칙을 PostgreSQL의 키, 제약, 인덱스와 RLS로 강제한다.

## 2. 설계 기준

- 외부에 노출되는 식별자는 애플리케이션이 생성한 UUIDv7을 사용한다.
- FOCUS의 `BillingAccountId`, `SkuId`처럼 계약에 이미 존재하는 식별자는 `text`로 유지한다.
- 시간은 `timestamptz`, 월은 해당 UTC 월의 1일인 `date`로 저장한다.
- 단가는 `numeric(30,18)`, 확정 금액은 `numeric(30,6)`으로 저장한다.
- 상태값은 `text + check`로 제한해 마이그레이션 비용을 낮춘다.
- 테넌트 데이터에는 `billing_account_id`를 직접 두고 복합 FK로 부모 범위를 고정한다.

실행 가능한 정의는 [`database/postgresql/schema.sql`](../database/postgresql/schema.sql)에 둔다.

## 3. 테이블

| 책임 | 테이블 | 핵심 키 |
|---|---|---|
| 사용자·소속 | `app_user`, `billing_account`, `billing_membership` | 사용자·회사, 회사+사용자 |
| 보안 증거 | `security_audit_event` | 감사 UUIDv7 |
| 발생기 인증 | `usage_producer`, `producer_credential` | 회사+발생기, 자격 증명 UUIDv7 |
| 거부 기록 | `event_rejection` | 거부 UUIDv7 |
| 가격 | `pricing_sku`, `price_rate` | SKU, 가격 UUIDv7 |
| 월간 정산 | `settlement_job`, `settlement_attempt`, `settlement_validation`, `monthly_settlement` | 회사+월, 실행 UUIDv7 |

Spring Session JDBC 테이블은 사용하는 Spring Session 버전의 공식 스키마로 설치한다. `principal_name`에는 `user_id` 문자열만 저장하고 회사·역할은 저장하지 않는다.

## 4. 무결성

- 활성 소속은 사용자당 하나이며, 같은 회사·사용자 관계도 하나다.
- 마지막 활성 Admin의 역할 변경·종료는 회사 행 잠금 아래 거부한다.
- 가격 구간은 `[valid_from, valid_to)`이며 같은 SKU에서 겹칠 수 없다.
- 회사·월별 작업과 확정 결과는 하나이고 실행 중 시도도 하나다.
- 확정 금액은 차이가 0인 `VALIDATED` 실행의 재계산 금액과 같아야 한다.
- 감사와 확정 결과는 생성 후 변경·삭제할 수 없다.

역할 변경과 관련 세션 폐기·감사 추가는 한 애플리케이션 트랜잭션에서 처리한다. 월간 확정은 같은 트랜잭션에서 검증 확인 → 결과 생성 → 작업을 `FINALIZED`로 변경하는 순서로 처리하며, 지연 제약이 커밋 전에 둘의 존재를 다시 확인한다.

## 5. 조회와 인덱스

| 경로 | 인덱스 근거 |
|---|---|
| 사용자 소속 확인 | 활성 `user_id` 부분 유일 인덱스 |
| 회사 구성원 목록 | `billing_membership` 기본키의 회사 선두 열 |
| 가격 선택 | `sku_id, valid_from desc` |
| 배치 작업 선점 | 미확정 작업만 포함하는 `next_attempt_at` 부분 인덱스 |
| 실행 이력 | `billing_account_id, billing_month, started_at desc` |
| 확정 월 조회 | `billing_account_id, billing_month` 기본키 |

FK는 PostgreSQL이 자동 인덱싱하지 않으므로 부모 삭제·조인 경로에 필요한 자식 인덱스를 명시한다.

`clickhouse_price_rate_export`는 SKU 정의와 가격을 펼치고 `updated_at`을 단조로운 동기화 버전으로 변환한다. 단가는 바꾸지 않으며 열린 가격의 `valid_to`만 한 번 닫을 수 있다.

## 6. 테넌트 격리

사용자 API 트랜잭션은 다음 순서를 따른다.

```text
세션의 user_id 설정
→ 활성 BillingMembership 조회
→ billing_account_id를 SET LOCAL로 설정
→ RLS가 적용된 업무 쿼리
→ commit/rollback으로 컨텍스트 자동 제거
```

- 클라이언트가 보낸 회사 식별자는 세션 컨텍스트에 사용하지 않는다.
- RLS 정책은 현재 사용자 본인의 소속 조회와 현재 회사 데이터만 허용한다.
- 애플리케이션 계정은 테이블 소유자나 `BYPASSRLS` 권한을 갖지 않는다.
- 발생기 인증과 내부 배치는 사용자 세션 대신 명시적인 회사 범위를 설정한다.
- 가격표와 인증 전 거부 기록은 테넌트 RLS 대상이 아니며 전용 실행 계정의 최소 권한으로 격리한다.

RLS는 쿼리의 회사 조건 누락을 방어한다. 임의 SQL 실행이나 DB 계정 탈취까지 막는 경계로 간주하지 않는다.

## 7. 검증 기준

- 다른 회사 ID로 직접 조회해도 0행이어야 한다.
- 커넥션을 재사용해도 이전 요청의 회사 범위가 남지 않아야 한다.
- 두 Admin을 동시에 제거해도 마지막 한 명은 유지돼야 한다.
- 겹치는 가격 구간과 동시 실행 시도를 DB가 거부해야 한다.
- 실패 시도 뒤 새 시도는 가능하지만 확정 결과는 하나만 생성돼야 한다.
- FK 열 누락과 대표 조회의 실행 계획은 통합 테스트에서 점검한다.
