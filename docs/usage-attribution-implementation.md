# 사용량 귀속 구현

## 1. 완료 범위

**3번 사용량 귀속: 당시 회사 판단 → 저장 검증 → 내부 승인·복구까지 구현·로컬 인수 검증 완료.** 기존 점유 워커를 사용하며 새 서버·Kafka 소비자는 없다. 고객 공개·가격 계산·월간 확정·과거 정정은 미완료다.

```text
발견 → AttributionService: 판단·불변 개정 준비
                   ↓
       ApprovalService: ClickHouse 저장·검증
                   ↓
       HistoryGuard: 잠금·최신 이력 제공
                   ↓ 같은 트랜잭션
       ApprovalService: 근거 재검증 → WorkStore: 승인 기록

내부 조회: 잠금·차단 검사 → 승인 개정만 읽기
```

## 2. 책임과 안전장치

| 책임 | 구현한 보장 |
|---|---|
| 발견 | `(source,id)` 상한을 고정해 500건씩 반복 탐색. 작업 등록/진도는 원자적이며 앞쪽 지연 입력은 다음 회차에서 발견 |
| 판단 | 세 측정값 전체가 확인된 한 점유에 포함되어야 한다. 현재 회사 대체·구간 분할 없음 |
| 준비·저장 | 30초 임대와 실행 토큰으로 오래된 실행자 차단. 불변 개정을 재전송하고 일반 뷰로 중복 제거·내용 충돌 검사 |
| 승인 | CH 호출 후 VM 잠금 아래 버전·확인 범위·오류·작업 소유권 재검증. PG가 승인한 개정만 유효 |
| 내부 조회 | 회사·승인·현재 차단 상태 확인 후 VM 잠금 안에서 결과 읽기. 장애는 `AttributionUnavailable`, 타사/미승인/차단은 `Withheld` |
| 운영 | `Waiting`은 자동 재확인, `Failed`는 권한 있는 재시도. 결과 타입을 저장 경계까지 유지하며 예약만으로 오류를 해제하지 않음 |

승인 판단은 모듈 내부 `ApprovalService`만 담당하며 우회 호출은 구조 테스트로 막는다. `WorkStore`는 저장된 준비 내용과 정확히 같은 승인만 기록한다. `HistoryGuard`는 승인자가 아니다. 잠금 아래 미확인·충돌 결과도 그대로 전달하고, 콜백 실패 시 같은 트랜잭션의 쓰기를 롤백한다. 승인용 CH 검증은 잠금 밖, 내부 조회의 CH 읽기는 잠금 안에서 수행한다.

귀속 전용 PG 계정은 점유 테이블을 수정할 수 없다. 점유 모듈 소유의 제한 함수와 `HistoryGuard`만 동일 트랜잭션에 참여한다. PG에는 작업·불변 준비/전이·승인·오류·운영 감사를 보존한다. CH에는 검증용 전체 payload를 고정하며 고객용 OLAP 조회 모델로 간주하지 않는다.

## 3. 실행과 검증

```sh
./gradlew check
bash scripts/verify-attribution.sh
./gradlew :apps:occupancy-worker:bootJar
docker compose -f compose.yaml -f compose.occupancy.yaml -f compose.attribution.yaml up -d occupancy-worker
```

추가 SQL: [PostgreSQL](../database/postgresql/attribution.sql), [ClickHouse](../database/clickhouse/attribution.sql). 새 볼륨에는 자동 설치한다. **기존 볼륨은 삭제하지 않고 배포 소유자가 SQL을 적용한다.** 사용자 DB에는 이번 작업에서 적용하지 않았다. Compose 자격증명은 로컬 전용이다.

직접 실행은 기존 `OCCUPANCY_DB_URL/PASSWORD`에 `ATTRIBUTION_ENABLED=true`, `ATTRIBUTION_DB_URL/PASSWORD`, `ATTRIBUTION_CLICKHOUSE_URL/PASSWORD`를 추가한다. 기본은 비활성이다.

- 순수 규칙: 과거 회사·구간 경계·유휴·다중 점유·확인 부족·충돌·UInt64 상한.
- 승인 경계 17건과 구조 검사: 우회 호출, 저장 불명확·충돌, 이력 변경, 소유권 만료, 마감, 잠금 안팎 호출 순서.
- 실제 PG·CH 25건: 등록/진도 rollback, 지연 발견, 동시 확보·만료 실행자, 재전송·내용 충돌, 승인/조회 잠금·후속 오류, 타사 차단, 운영 복구. 불변 준비 내용 대조·콜백 rollback·트랜잭션 연결 오류도 검증.
- 별도 JVM을 CH 저장 후 강제 종료하고 재시작해 같은 개정으로 승인한다. 기존 점유 Kafka 복구 검사는 별도로 유지한다.

고정 구간표·DB 상태·승인 개정으로 판단한다. 경합은 latch/`NOWAIT`로 재현하며 프로세스 polling은 완료 조건 대기용이다. 스크립트는 자체 테스트 컨테이너만 생성·정리한다. 로컬 통과와 새 원격 CI 실행 결과는 구분한다.

최종 인수는 [AttributionFlowTest](../apps/occupancy-worker/src/test/java/io/github/bbororo5/cloudbilling/worker/AttributionFlowTest.java)의 실제 워커 JVM으로 검증한다. 작업 등록·귀속 처리를 테스트가 대신 호출하지 않는다.

```text
두 VM의 원시 사용량·중복 적재 → 자동 발견 → 당시 회사로 승인
 → 한 VM 이력 충돌 → 해당 VM만 조회 보류
 → 워커 강제 종료·재시작 → 기존 개정 유지 + 다른 VM 신규 입력 승인
```

이 검사는 ClickHouse 원시 원장부터 내부 조회까지다. Kafka 수집·점유 사실 수신은 별도 회귀 검사로 연결 경계를 검증하며, 고객 HTTP 경로의 검증으로 간주하지 않는다.

## 4. 남은 경계

- 알림에는 VM·이벤트·구간·알려진 회사·사유를 포함한다. 실제 운영 인증·외부 채널은 미연결이며 기본 채널은 실패를 영속 재시도한다.
- 스캔 행/신규 수/시간, 상태별 작업 수, 가장 오래된 대기, 오류·미전달 알림, 실패 단계를 측정한다. 반복 스캔·대기 이력 비용과 처리량은 아직 부하 검증하지 않았다.
- 이미 확정된 UTC 월과 겹치는 신규 승인은 보류하며 정산 결과는 수정하지 않는다. 동시 마감 프로토콜·월 경계 배분은 후속 작업이다.
- 회사 간 재귀속·준비 내용 충돌은 단순 재시도로 우회하지 않는다. 과거 편집·정정 절차는 제공하지 않는다.
- 내부 조회의 회사 인자는 신뢰된 호출자 계약이다. **BFF 인증/인가·CH 테넌트 신원/Row Policy 검증 전까지 고객 권한은 닫는다.**
- 전원·디스크 영구 손실, 장기 보관, 운영 TLS·비밀 관리와 대규모 성능은 이번 검증 범위가 아니다.
