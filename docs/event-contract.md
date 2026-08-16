# Event Contract v1

> 상태: 교체 예정. ADR-008에 따라 VM 입력에서 회사·SKU 필드를 제거하는 v2 계약을 테넌트 격리 논의 후 확정한다. 현재 문서는 기존 구현 기준이며 새 설계의 정답이 아니다.

## 1. 목적

인스턴스의 1분 사용량을 CloudEvents 형식으로 전달하고, FOCUS 용어로 Compute·Storage·Network 사용량을 표현한다.

## 2. 적용 표준

| 영역 | 적용 |
|---|---|
| 이벤트 외형 | CloudEvents 1.0.2 Structured JSON, wire `specversion: 1.0` |
| 사용량 | FOCUS 1.4 Cost and Usage의 측정 관련 필드 |
| 시간 | UTC RFC 3339, 시작 포함·종료 제외 |
| 검증 | [JSON Schema](../contracts/v1/instance-usage-event.schema.json), [예시 이벤트](../contracts/v1/examples/instance-usage-event.json) |

입력은 비용이 계산되기 전이므로 FOCUS 전체 준수를 주장하지 않으며, 사용량 측정에 필요한 표준 필드만 사용한다.

## 3. CloudEvents 계약

| 필드 | 규칙 |
|---|---|
| `id` | 발생기가 생성하고 재시도 시 유지하는 UUID |
| `source` | `urn:cloud-usage:meter:{producerId}` |
| `type` | `io.github.bbororo5.cloudusage.instance.usage.v1` |
| `subject` | `instances/{ResourceId}` |
| `time` | 사용 구간의 `ChargePeriodEnd` |
| `datacontenttype` | `application/json` |
| `dataschema` | v1 JSON Schema의 절대 URI |
| `data` | 같은 인스턴스·구간의 사용량 레코드 3개 |

동일 이벤트의 식별자는 `source + id`이며 Kafka 파티션 키는 `BillingAccountId + ResourceId`다.

## 4. FOCUS 사용량 레코드

모든 레코드는 `BillingAccountId`, `ChargePeriodStart/End`, `RegionId`, `ResourceId`, `ResourceType`, `ServiceCategory`, `ServiceName`, `SkuId`, `SkuMeter`, `ConsumedQuantity/Unit`을 갖는다.

| 사용량 | `ServiceCategory` | `SkuMeter` | 수량·단위 |
|---|---|---|---|
| VM 실행 | `Compute` | `Compute Usage` | 실행 초·`Second` |
| 블록 스토리지 | `Storage` | `Block Volume Usage` | 할당 GiB × 초·`GiB-Second` |
| 외부 전송 | `Networking` | `Data Transfer` | 전송 바이트·`Byte` |

## 5. 검증 규칙

- 일반 구간은 60초이며 종료 시 마지막 구간만 60초 미만일 수 있다.
- 한 이벤트의 세 레코드는 계정·자원·리전·구간이 같고 각 사용량이 한 번씩 존재한다.
- 모든 수량은 0 이상의 정수다.
- 같은 인스턴스의 사용 구간은 겹치지 않는다.
- 단가·통화·금액과 수신 시각은 입력에 포함하지 않는다.

구간 비교와 레코드 간 동일성처럼 JSON Schema만으로 표현하기 어려운 규칙은 수신 검증에서 적용한다.

## 6. 수신 기준

- 발생기는 `credentialId.secret` 형식의 Bearer 자격 증명을 사용한다.
- 서버는 secret 원문을 저장하지 않고 256비트 난수 secret의 SHA-256 해시만 저장한다.
- 자격 증명의 계정·`source`와 이벤트가 일치해야 한다.
- `202 Accepted`는 이벤트가 Kafka 브로커에 내구성 있게 기록된 뒤 반환한다.
- 거부 이력에는 원본 payload를 남기지 않는다.
