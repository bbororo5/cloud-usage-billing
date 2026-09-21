# 수집 구현·검증

## 범위

발생기 → Kafka → ClickHouse 원장·일반 뷰를 구현한다. 회사 귀속·가격 계산은 제외한다. 기존 개발 볼륨은 자동 변경하지 않는다.

## 실행 설계

- 발생기는 명시된 VM·시작 시각·완료된 60초 구간으로 이벤트를 만든다. 같은 실행 입력의 재실행은 같은 ID·내용을 발행한다. ACK 전 실패는 같은 이벤트로 재시도하며 실패를 성공으로 세지 않는다.
- Kafka 입력 테이블은 CloudEvents JSON과 배열을 파싱한다. 적재용 MV 하나가 공통 열·배열·전달 위치를 원장에 저장한다. 추가 처리 서버는 없다.
- 원장은 `MergeTree`, 논리 읽기는 일반 뷰다. 이벤트 전체를 하나의 튜플로 선택해 전달 사본 간 열 혼합을 막는다.
- 원장은 `fsync_after_insert=1`, `fsync_part_directory=1`, 소비는 `kafka_commit_every_batch=0`, `kafka_commit_on_select=0`, 오류 건너뛰기 0이다. 비동기 중간 저장소는 없다.
- 로컬 기본값: flush 1초, 발생기 재시도 간격 1초, Kafka 보관 7일. 내장 소비는 엔진의 재시도 동작을 사용한다. 지속 실패 1분·가장 오래된 미처리 메시지 1일에 경고한다. 경고는 폐기·offset 이동을 수행하지 않는다. 용량·처리량은 별도 측정한다.

## 테스트 판정

| 대상 | 입력·장애 | 기대 결과 |
|---|---|---|
| 발생기 | 구간·수량 경계, 재실행, ACK 성공/실패 | 계약 준수, 같은 ID·내용, ACK 뒤에만 성공 |
| 원장·뷰 | 여러 묶음의 재전달, 역순, 같은 ID의 다른 source, UInt64 최대값 | 물리 사본 보존, 이벤트당 측정값 3개, 논리 사용량 불변 |
| 연결 | 실제 발생기 → Kafka → 내장 소비 | 입력 ID·수량 일치, 적재 후 offset 진행 |
| 복구 | 적재 실패, 소비 재시작, 재발행 | 실패 위치를 넘기지 않음, 복구 후 누락·중복 반영 없음 |
| 접근 | 잘못된 발행 자격·토픽, BFF 원장·뷰 접근 | 거부 |

프로세스 종료는 전원 장애가 아니다. OS 캐시 소실·디스크 영구 손실은 이 테스트의 성공으로 보증하지 않는다. 실제 실행 결과와 미검증 범위를 구분한다.

## 실행

Java 21·Docker Compose v2가 필요하다. 실제 VM 측정 대신 완료된 점유 구간을 명시하는 재현 가능한 발생기를 사용한다. 같은 명령의 재실행은 ACK를 받은 이벤트까지 다시 보낼 수 있지만 논리 사용량은 증가하지 않는다. 입력 계획을 보존해야 하며 새 ID를 임의 생성해 재시작하지 않는다.

```sh
./gradlew :apps:usage-generator:installDist --no-daemon
bash scripts/start-ingestion.sh --initialize-new-group  # 최초 한 번만
docker compose run --rm generator --config /config/kafka-producer.properties \
  --source demo-vm --resource demo-vm --from 2026-08-12T00:00:35.123Z \
  --seconds 180 --pace-ms 0
bash scripts/check-ingestion.sh
```

`--pace-ms` 기본값은 60000이며 0은 빠른 재생이다. `--vms`로 독립 출처 여러 개를 생성할 수 있다. 실행 계획은 종료된 점유 구간이며 잔여 60초 미만을 버린다. 내부 자원 할당의 점유 제어·실제 VM 측정은 구현하지 않는다. 재시작은 `start-ingestion.sh`를 옵션 없이 실행한다. 기존 그룹의 offset 초기화는 거부한다.

로컬 전용 SASL/PLAIN·ACL을 사용하며 Kafka·ClickHouse 포트는 호스트에 공개하지 않는다. 발생기에는 자기 자격증명만 마운트한다. 운영 배포에는 TLS·비밀 관리가 필요하며 공개된 로컬 비밀번호를 사용하면 안 된다. 모니터 명령은 운영자 자격을 별도 마운트하고 소비 위치를 변경하지 않는다.

`check-ingestion.sh`는 정상 0, 경고 2, 조회 실패 등 실행 오류는 비정상 종료한다. 운영자가 주기 실행·알림 채널에 연결해야 하며 현재 콘솔 경고를 외부 통지 완료로 주장하지 않는다. 메시지 나이는 Kafka 기록의 timestamp 기준이다.

## 검증 범위와 한계

- `verify-clickhouse-storage.sh`: 배열 저장·다른 묶음/역순 중복·출처 분리·정밀도·일반 뷰·동기화 설정·가격 사본 회귀.
- `verify-ingestion.sh`: 실제 3브로커와 ClickHouse에서 매핑·재발행·저장 실패·프로세스 종료·이전 offset 재생·접근 거부·복제본 장애·소비 실패/적체 경고를 검사한다. 이전 offset 재생은 중복 복구 결과를 검증하며 commit 응답 유실 자체를 재현하지 않는다.
- 전원/커널 강제 종료·fsync 호출 자체의 실패 주입·디스크 영구 손실·보관 만료 실험과 대규모 처리량은 미검증이다. 기존 개발 볼륨 마이그레이션도 수행하지 않았다. 기존 볼륨은 보존하고 새 `ingestion-*` 볼륨을 사용한다.

## 근거

[ClickHouse Kafka 내구성](https://clickhouse.com/docs/reference/engines/table-engines/integrations/kafka#data-durability): 모든 적재 대상의 파일·디렉터리 동기화와 insert 완료 후 commit이 필요하다.
