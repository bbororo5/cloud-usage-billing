# 점유 이력 워커

외부 점유 사건을 PostgreSQL 수신함에 보관한 뒤 VM별 이력으로 반영한다. **수신 보관 후 Kafka commit / 이력·진도·오류 해결은 별도 원자적 트랜잭션**이다. 기존 사용량 → ClickHouse 경로는 변경하지 않는다.

```text
Kafka → 수신함 → VM별 반영 → 점유 이력 ← 내부 조회 + 버전 캐시
                    ↓
                오류·감사·알림 재시도
```

## 실행

Java 21, Docker 필요. 로컬 전용 계정·평문 SASL 구성이며 운영 배포용이 아니다.

```sh
./gradlew :apps:occupancy-worker:bootJar
docker compose -f compose.yaml -f compose.occupancy.yaml up -d occupancy-worker
```

새 PostgreSQL 볼륨에는 스키마가 자동 설치된다. **기존 볼륨은 삭제하지 말고**, 배포 소유자로 아래 추가 SQL을 적용한 뒤 워커를 시작한다.

```sh
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U billing_owner -d billing < database/postgresql/occupancy.sql
```

직접 실행 시 `OCCUPANCY_DB_URL`, `OCCUPANCY_DB_PASSWORD`, `OCCUPANCY_KAFKA_CONFIG`를 제공한다. Kafka 설정 파일은 bootstrap·인증 설정을 담는다. 수동 commit·자동 토픽 생성 금지는 코드에서 강제하며 기존 그룹 offset은 초기화하지 않는다.

점유 토픽은 로컬 기본 3파티션·복제 3·최소 ISR 2·7일 보관이다. 발행 신원은 `occupancy-infra`, 읽기 신원은 `occupancy-worker`다. 외부 발생기의 sequence 영속화는 구현하지 않았다.

## 검증

```sh
./gradlew check
OCCUPANCY_KAFKA_TESTS=true bash scripts/verify-occupancy.sh
bash scripts/verify-occupancy-stack.sh
```

- 빠른 검사: 계약·컴파일된 의존 경계·순수 전이·잘못된 wire·저장/commit 실패.
- PostgreSQL: 중복·누락·동시 반영·rollback·중첩 거부·최소 권한·운영 요청 멱등성·동일 스냅샷 조회·캐시 실패.
- Kafka: 실제 미커밋 재전달, 별도 JVM 강제 종료·재시작, 한 VM 누락 중 다른 VM 진행. 테스트에만 짧은 그룹 세션 시간을 사용한다.
- 로컬 스택: 3브로커 SASL/ACL 연결, 실제 워커 반영, 소비 신원의 쓰기 거부.

스크립트는 자기 테스트용 컨테이너·볼륨만 생성하고 정리한다. Kafka 수신함 복구 시험은 단일 브로커, 복제본 장애 시험은 기존 `verify-ingestion.sh`가 담당한다. 전원·디스크 영구 손실·보관 만료 보장은 별도다.

## 운영 경계

내부 `HistoryReader`와 잠금 재검증용 `HistoryGuard`를 귀속 모듈에 공개한다. 고객/관리 HTTP API는 없다. `IssueRetry`는 신뢰된 운영 어댑터 전용이다. 실제 운영 인증·외부 알림 채널은 아직 없다. 기본 알림 어댑터는 성공을 가장하지 않고 `PENDING`을 유지한다.

기본값은 캐시 1,000개(한 결과 1,000구간 초과는 캐시 제외), 반영 회차 100 VM·VM당 1사건, 장기 대기 경고 300초다. 성능 달성 수치가 아니며 설정으로 조절한다. 캐시 키는 `(source, version, from, to)`이고 매 조회에서 DB 상태를 확인한다.

Micrometer에 수신 수·실패 단계·미반영 수/연령·확인 지연·미전달 알림·DB 크기·캐시 hit/miss와 DB/Kafka 준비 상태를 기록한다. 공개 관리 포트는 열지 않으며 지표 수집기 연결·용량 경보 임계값은 운영 배포 과제다. 원문·회사 상세를 공용 로그에 출력하지 않는다.

## 사용량 귀속 모듈

같은 워커 안에 반복 발견·과거 회사 귀속·불변 개정 저장·내부 승인·운영 복구를 추가했다. 실행은 기본 비활성이며 [귀속 구현·검증](../../docs/usage-attribution-implementation.md)에 설정과 테스트를 정리했다.

과거 정정, 고객 공개, 비용 계산, 월간 확정, 실제 VM 제어는 완료 범위가 아니다.
