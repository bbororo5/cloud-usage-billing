# PostgreSQL access tests

실제 `billing_bff`·`billing_batch` 계정의 허용 작업과 금지 작업을 함께 검사한다. 제품 스키마·권한을 그대로 적용하며 관리자 계정은 초기 데이터 준비에만 사용한다.

## 실행

Java 21과 실행 중인 Docker가 필요하다.

```bash
bash scripts/verify-postgresql-access.sh
```

PostgreSQL 17.5 임시 컨테이너와 임의의 로컬 포트를 사용한다. 테스트별 트랜잭션을 rollback하고, 성공·실패 모두 종료 시 임시 컨테이너와 데이터를 제거한다. 기존 Compose DB·볼륨은 사용하지 않는다.

`accessTest`는 실제 DB가 필요한 별도 Gradle 작업이다. 일반 `test` 및 기존 계약 CI에는 포함되지 않으며, 실패를 skip이나 성공으로 바꾸지 않는다. 결과는 `build/reports/tests/accessTest/index.html`에 생성한다.

## 검사 범위와 판정

- 문서에서 독립적으로 정의한 작업 허용 목록과 실제 SQL 실행을 비교한다. 권한 거부는 SQLSTATE `42501`, 정상 쓰기는 영향 행 수·조회 결과로 판단한다.
- 가격 export는 수정 불가능한 조인 뷰여서 권한 확인보다 먼저 `55000`이 발생한다. 이 경우 실제 거부와 **쓰기 권한 부재**를 각각 검사한다.
- 회사별 조회, 소속·작업의 교차 회사 쓰기, 회사 문맥 없는 접근, 같은 연결의 commit/rollback 후 문맥 제거를 검사한다.
- 계정 속성·역할 소속·테이블 소유권 및 실제 역할 전환 거부를 검사한다.

HTTP 인증·현재 사용자 역할 검사, 연결 풀 동작, 모든 테이블의 RLS 쓰기 조합, 동시 변경 경합, ClickHouse 격리는 이 테스트의 통과 범위가 아니다.

## 2026-09-18 실행 결과

**104건 중 102건 통과, 정상 기능 2건 실패.** 권한·제품 코드는 변경하지 않았다.

| 실패 | 확인된 원인 |
|---|---|
| 다른 Admin이 남아 있는 상태에서 Admin 강등 | `protect_last_admin()`의 `billing_account FOR UPDATE`가 BFF 권한으로 거부됨 |
| 검증 완료 후 월간 확정 | `enforce_monthly_settlement()`의 `settlement_validation FOR KEY SHARE`가 배치 권한으로 거부됨 |

다음은 **변경 권한을 넓히지 않으면서 필요한 잠금을 수행할 경계**를 설계·승인하는 일이다. 잠금 제거 또는 테스트 제외로 통과시키지 않는다. 기존 관리자 기반 스키마 테스트의 성공과 구분한다.
