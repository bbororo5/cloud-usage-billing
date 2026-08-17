# ClickHouse Physical Data Model

> 상태: 승인 — ADR-006 및 ADR-008 반영. 원시 사용량 원장의 테넌트 비인지 물리 구조와 귀속 조회 모델의 분리 경계를 정의한다.

## 1. 목적

At-least-once로 전달된 원시 사용량을 잃지 않고 저장하며, 회사–VM 점유 이력과 결합된 귀속 조회 모델을 통해 테넌트 격리된 비용 조회와 월간 재계산을 안전하게 지원한다.

실행 가능한 정의는 [`database/clickhouse/schema.sql`](../database/clickhouse/schema.sql)에 둔다. 기준 버전은 ClickHouse `26.3 LTS`다.

## 2. 선택

| 대상 | 선택 | 근거 |
|---|---|---|
| 원시 사용량 원장 | `MergeTree` 전달 레코드 (테넌트 비인지) | 모든 Kafka 전달 사본을 보존하고 쿼리/귀속에서 명시적으로 중복 제거한다. 회사 식별자를 갖지 않는다. |
| 원시 원장 파티션 | 사용 시작 UTC 월 (`toYYYYMM(charge_period_start)`) | 월간 수명주기·재계산 단위와 일치하며 파티션 수가 제한된다. |
| 원시 원장 정렬 | 출처(`event_source`) → 사용 시각(`charge_period_start`) → 이벤트 ID(`event_id`) → meter | VM 출처별 시계열 조회와 중복 판별, 점유 구간 결합을 최적화한다. |
| 귀속 조회 모델 | 테넌트 격리 조회 모델 (Row Policy 적용) | 점유 이력으로 회사를 확정한 조회 모델이다. BFF는 이 모델만 읽으며 테넌트별 DB 신원과 Row Policy로 자기 회사 행만 허용한다. (물리 형태는 3단계에서 확정) |
| 가격 사본 | `ReplacingMergeTree(sync_version)` | PostgreSQL 가격의 수정 사본을 버전으로 교체하며 작은 테이블만 `FINAL`로 읽는다. |
| 선집계 | 사용하지 않음 | 먼저 요청 시 집계 성능을 측정하고 한계에 도달할 때만 추가한다. |

`ReplacingMergeTree`의 중복 제거는 백그라운드 merge 시점에 의존하므로 원시 사용량 정확성의 근거로 삼지 않는다. 공식 문서도 merge만으로 중복 부재를 보장하지 않는다고 명시한다: [ReplacingMergeTree](https://clickhouse.com/docs/reference/engines/table-engines/mergetree-family/replacingmergetree).

## 3. 원시 사용량 원장

`usage_record_delivery` 한 행은 Kafka에서 전달된 이벤트의 서비스별 레코드 하나다.

- 이벤트 하나가 Compute·Storage·Networking 세 행으로 펼쳐지므로 월 8.6억 이벤트는 약 25.8억 논리 사용량 행이다.
- 논리 레코드 키: `event_source + event_id + sku_meter`
- 물리 전달 키: `kafka_topic + kafka_partition + kafka_offset + sku_meter`
- 같은 이벤트 키(`event_source`)는 Kafka의 같은 파티션으로 라우팅한다.
- `payload_hash`가 다른 동일 논리 키는 자동 보정하지 않고 데이터 이상으로 처리한다.
- 원시 원장에는 `billing_account_id`를 저장하지 않으며, BFF 계정의 접근을 원천 차단한다.
- 수집 및 귀속 프로세스는 논리 키별 최대 `(kafka_partition, kafka_offset)` 행을 선택하여 중복을 제거한다.

## 4. 가격 사본

`price_rate_snapshot`은 PostgreSQL `price_rate`의 재생성 가능한 사본이다.

- `sync_version`이 큰 사본이 최신이다.
- SKU의 서비스·meter·unit도 함께 복사해 사용량 계약과 조인한다.
- 조회와 배치는 작은 가격 테이블에만 `FINAL`을 사용한다.
- 사용 구간을 완전히 포함하는 가격이 정확히 하나여야 한다.
- 가격이 없거나 겹치면 0원으로 처리하지 않고 조회·배치를 실패시킨다.
- 가격 동기화와 배치 확정은 PostgreSQL 가격 버전을 기준으로 검증한다.

## 5. 조회 영향 및 테넌트 격리

| 조회 | 물리 구조의 지원 |
|---|---|
| 원시 원장 적재·귀속 | `event_source` 및 시간 정렬로 점유 이력 매핑과 중복 제거 지원 |
| 월 누적·기간 비용 | 귀속 조회 모델의 회사·일 선두 정렬과 월 파티션 제거 |
| 서비스 구성·추이 | 귀속 모델의 서비스 및 사용 시각 정렬 |
| 리소스 Top N·상세 | 귀속 모델의 회사 범위 안 리소스 정렬 |
| 원본 커서 | `charge_period_end, event_source, event_id, sku_meter` 안정 정렬 |
| 월간 재계산 | 해당 월 파티션과 점유 이력 결합 스캔 |

- ClickHouse의 `ORDER BY`는 디스크 정렬과 sparse index를 결정한다.
- BFF는 테넌트별 DB 신원으로 ClickHouse에 연결하며, Row Policy가 귀속 모델에서 타 테넌트 행을 차단한다.
- 테넌트별 파티션이나 테넌트별 물리 테이블 분할은 하지 않고 단일 공유 테이블/뷰 구조를 유지한다.

## 6. 검증 기준

- 같은 이벤트를 반복 적재해도 중복 제거 후 사용량과 비용이 변하지 않아야 한다.
- 순서를 바꿔 적재해도 사용 시각 기준 결과가 같아야 한다.
- 점유 이력 구간(`[validFrom, validTo)`)과 결합해 정확한 회사로 귀속되어야 한다.
- BFF 계정으로 원시 원장(`usage_record_delivery`)에 접근 시 권한 거부(Permission Denied)되어야 한다.
- 테넌트별 DB 신원으로 조회 시 다른 회사의 데이터가 일절 노출되지 않아야 한다.
- 가격 사본의 이전 버전이 남아 있어도 최신 버전 하나만 선택돼야 한다.
- 31일 조회와 월간 배치가 함께 실행될 때 목표 응답시간과 자원 한도를 부하 테스트로 검증한다.
