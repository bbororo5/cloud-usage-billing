# PostgreSQL access tests

실제 `billing_bff`·`billing_batch` 계정의 허용 작업과 금지 작업을 함께 검사한다. 제품 스키마·권한을 그대로 적용하며 관리자 계정은 초기 데이터 준비에만 사용한다.

## 실행

Java 21과 실행 중인 Docker가 필요하다.

```bash
bash scripts/verify-postgresql-access.sh
```

PostgreSQL 17.5 임시 컨테이너와 임의의 로컬 포트를 사용한다. 테스트별 트랜잭션을 rollback하고, 성공·실패 모두 종료 시 임시 컨테이너와 데이터를 제거한다. 기존 Compose DB·볼륨은 사용하지 않는다.

`accessTest`는 실제 DB가 필요한 별도 Gradle 작업이다. 일반 `test`에는 포함되지 않으며, CI의 `postgres-access` 작업에서 실행한다. 같은 임시 컨테이너의 별도 DB에서 기존 SQL 회귀 검사도 실행한다. 결과는 `build/reports/tests/accessTest/index.html`에 생성한다.

## 검사 범위와 판정

- 문서에서 독립적으로 정의한 작업 허용 목록과 실제 SQL 실행을 비교한다. 권한 거부는 SQLSTATE `42501`, 정상 쓰기는 영향 행 수·조회 결과로 판단한다.
- 가격 export는 수정 불가능한 조인 뷰여서 권한 확인보다 먼저 `55000`이 발생한다. 이 경우 실제 거부와 **쓰기 권한 부재**를 각각 검사한다.
- 회사별 조회, 소속·작업의 교차 회사 쓰기, 회사 문맥 없는 접근, 같은 연결의 commit/rollback 후 문맥 제거를 검사한다.
- 계정 속성·역할 소속·테이블 소유권 및 실제 역할 전환 거부를 검사한다.
- 검증 함수 소유자의 제한된 권한·RLS, 앱의 함수 재사용·변경 거부, 임시 테이블을 통한 이름 가로채기 방어를 검사한다. `NOLOGIN` 역할의 RLS 검사는 준비용 관리 연결에서 해당 역할로 전환한 뒤 실행하며 앱 로그인 검사와 구분한다.

HTTP 인증·현재 사용자 역할 검사, 연결 풀 동작, 모든 테이블의 RLS 쓰기 조합, 동시 변경 경합, ClickHouse 격리는 이 테스트의 통과 범위가 아니다.

## 2026-09-18 실행 결과

**104건 중 102건 통과, 정상 기능 2건 실패.** 권한·제품 코드는 변경하지 않았다.

| 실패 | 확인된 원인 |
|---|---|
| 다른 Admin이 남아 있는 상태에서 Admin 강등 | `protect_last_admin()`의 `billing_account FOR UPDATE`가 BFF 권한으로 거부됨 |
| 검증 완료 후 월간 확정 | `enforce_monthly_settlement()`의 `settlement_validation FOR KEY SHARE`가 배치 권한으로 거부됨 |

## 2026-09-20 수정·회귀 결과

방어 테스트 추가 후 110건 중 8건 실패를 확인했다. 검증 함수별 `NOLOGIN` 소유자·최소 열 권한·`SECURITY DEFINER`를 적용하고 소유자 RLS 검사까지 추가해 **112건 모두 통과**했다. 앱의 테이블·열 권한은 넓히지 않았으며 기존 잠금도 유지했다. 기존 SQL·Java·계약 회귀 검사도 통과했다.

배포 정의는 `database/postgresql/guard-privileges.sql`, 설계는 [물리 모델](../../docs/postgresql-physical-data-model.md#검증-함수의-권한)을 따른다. 동시 변경 경합과 HTTP 역할 검증은 별도 후속 작업이다.
