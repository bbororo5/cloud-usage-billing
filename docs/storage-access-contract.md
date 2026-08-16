# Storage Access Contract

> 상태: ADR-008 반영 대기. 아래 회사 기반 ClickHouse 접근은 테넌트 격리 후속 결정 전의 기존 계약이다.

## 1. 목적

PostgreSQL·Kafka·ClickHouse 사이에서 데이터가 언제 안전하게 기록되고, 어떤 범위와 규칙으로 읽히는지 정의한다.

## 2. 사용자 요청

```text
세션 user_id 확인
→ PostgreSQL 트랜잭션에서 user_id SET LOCAL
→ 본인 활성 소속 조회
→ billing_account_id SET LOCAL
→ 현재 역할 확인과 RLS 적용 조회
→ PostgreSQL 트랜잭션 종료
→ 명시적 TenantScope로 ClickHouse 조회
```

- PostgreSQL 커넥션은 종료 전에 반환하지 않는다.
- ClickHouse 호출 전에 PostgreSQL 커넥션을 반환해 두 저장소를 동시에 점유하지 않는다.
- ClickHouse 쿼리는 `billing_account_id` 없는 진입점을 제공하지 않는다.

## 3. 이벤트 적재

```text
발생기 인증·계약 검증
→ Kafka에 CloudEvents source를 key로 기록
→ broker 내구성 ACK 후 202
→ consumer가 이벤트를 서비스별 3행으로 변환
→ ClickHouse batch insert 성공
→ Kafka offset commit
```

- 재시작·재시도로 같은 전달이 다시 적재될 수 있다.
- 원장은 회사 정보 없이 VM source와 Kafka topic·partition·offset을 보존하고, 조회는 논리 키별 전달 사본을 하나로 취급한다.
- 동일 논리 키의 `payload_hash`가 둘 이상이면 계산하지 않고 데이터 이상으로 처리한다.
- ClickHouse 실패 중에는 offset을 진행하지 않아 Kafka에서 복구한다.

## 4. 가격 동기화

PostgreSQL `clickhouse_price_rate_export`가 가격 사본의 유일한 입력이다.

```text
PostgreSQL 가격 export 읽기
→ ClickHouse price_rate_snapshot batch insert
→ 최신 sync_version과 행 수 검증
→ 계산 가능 상태로 공개
```

- MVP에는 가격 변경 API가 없으므로 배포 시 동기화하고 서비스 시작 전에 검증한다.
- 새 가격 버전은 적용 시각 전에 같은 명령으로 동기화한다.
- 반복 동기화는 같은 `sync_version`을 써도 안전하다.
- 사본 누락·중복 가격은 0원으로 숨기지 않고 API 503 또는 배치 실패로 처리한다.

## 5. 비용·원본 조회

- 비용은 회사·기간으로 원장을 제한하고 중복 제거 → 가격 조인 → 그룹화 순서로 계산한다.
- 기간 경계는 분 단위이며 `[from, to)` 안에 완전히 포함된 사용 구간을 계산한다.
- 금액은 ClickHouse에서 scale 18로 계산하고 PostgreSQL 확정 시 scale 6으로 반올림한다.
- `dataAsOf`는 선택된 논리 레코드의 최대 `charge_period_end`다.
- 원본 첫 페이지는 `snapshot_ingested_at`을 정하고 후속 커서에 서명해 포함한다.
- 커서 정렬 키는 `charge_period_end + source + id + sku_meter`다.

대표 쿼리는 [`database/clickhouse/queries`](../database/clickhouse/queries)에 둔다. 실제 API의 동적 필터·그룹은 허용 목록으로 조립하고 값은 바인딩한다.

## 6. 월간 확정

1. Kafka 적체와 ClickHouse 적재 지연이 0인지 확인한다.
2. 회사·월 작업을 잠그고 새 실행 시도를 만든다.
3. 현재 예상 총액을 기록한 뒤 같은 닫힌 월을 다시 계산한다.
4. 데이터 이상 0건, 가격 동기화 일치, 두 금액 차이 0원을 검증한다.
5. PostgreSQL 한 트랜잭션에서 검증·확정 결과를 만들고 작업을 종료한다.

재시도는 새 `run_id`를 사용하며 완료된 시도와 기존 확정 결과를 덮어쓰지 않는다.

## 7. 실패 기준

| 상황 | 동작 |
|---|---|
| Kafka 기록 실패 | 수신 성공으로 응답하지 않는다. |
| ClickHouse 적재 실패 | offset을 commit하지 않고 재시도한다. |
| payload 충돌·가격 누락 | 해당 비용 응답과 월 확정을 실패시킨다. |
| 가격 사본 지연 | 마지막 사본으로 조용히 계산하지 않고 버전 검증에 실패한다. |
| PostgreSQL 권한 확인 실패 | 기본 차단한다. |
| 배치 중단 | 시도를 실패로 남기고 새 시도로 재실행한다. |
