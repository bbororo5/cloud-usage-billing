# Event Contract

> 상태: 승인 — ADR-006 및 ADR-008 반영. VM 입력에서 회사·과금·가격 필드를 제거하고 `source` 중심의 과금 비인지 계약을 정의한다.

## 1. 목적

인스턴스의 1분 원시 사용량을 CloudEvents 형식으로 전달하고, 과금·테넌트 문맥 없이 Compute·Storage·Network 측정값을 표현한다.

## 2. 적용 표준

| 영역 | 적용 |
|---|---|
| 이벤트 외형 | CloudEvents 1.0.2 Structured JSON, wire `specversion: 1.0` |
| 원시 사용량 | Compute·Storage·Networking의 순수 측정 필드 (FOCUS 용어는 귀속·가격 결합 후 적용) |
| 시간 | UTC RFC 3339, 소수 초 생략 또는 1~3자리, 시작 포함·종료 제외 |
| 검증 | [JSON Schema](../contracts/v1/instance-usage-event.schema.json), [예시 이벤트](../contracts/v1/examples/instance-usage-event.json) |

입력은 비용 및 테넌트가 결정되기 전의 원시 측정이므로, 회사 식별자(`BillingAccountId`)나 가격 항목(`SkuId`)을 포함하지 않는다.

## 3. CloudEvents 계약

| 필드 | 규칙 |
|---|---|
| `id` | 발생기가 생성하고 재시도 시 유지하는 UUID |
| `source` | `urn:cloud-usage:meter:{producerId}` (VM 출처 식별자) |
| `type` | `io.github.bbororo5.cloudusage.instance.usage.v1` |
| `subject` | `instances/{ResourceId}` |
| `time` | 사용 구간의 `ChargePeriodEnd` |
| `datacontenttype` | `application/json` |
| `dataschema` | v1 JSON Schema의 절대 URI |
| `data` | 같은 인스턴스·구간의 사용량 레코드 3개 |

동일 이벤트의 논리 식별자는 `source + id`이며, Kafka 파티션 키는 VM 출처인 `source`다.

`data`의 측정값 3개는 CloudEvents가 강제한 형식이 아니라 프로젝트 계약이다. 입력은 이벤트 하나이며, 서비스별 행 전개는 저장·조회 설계의 선택이다.

## 4. 원시 사용량 레코드

모든 레코드는 `ChargePeriodStart/End`, `RegionId`, `ResourceId`, `ResourceType`, `Meter`, `ConsumedQuantity`, `ConsumedUnit`을 갖는다. 회사(`BillingAccountId`) 및 가격(`SkuId`) 정보는 포함하지 않는다.

| 사용량 | `Meter` | 수량·단위 |
|---|---|---|
| VM 실행 점유 | `Compute Usage` | 회사에 할당되어 실행 중인 점유 초·`Second` |
| 블록 스토리지 | `Block Volume Usage` | 할당 GiB × 초·`GiB-Second` |
| 외부 전송 | `Data Transfer` | 전송 바이트·`Byte` |

## 5. 발생기 보장 규칙

- 사용 구간은 정확히 60초이며 점유 시작 시각을 기준으로 연속 측정한다. 정각 분 경계에 맞출 필요는 없다.
- 종료·점유 변경 시 잔여 60초 미만은 발행하지 않고 측정을 초기화한다. 발생기는 회사 식별자 없이 점유 경계 신호만 사용한다.
- 점유 경계 신호는 과금 시스템 외부의 인프라 관리 측에서 제공하며 사용량 이벤트와 구분한다. VM 출처는 재점유 시에도 유지하고 유휴 중에는 정상 사용량 이벤트를 발행하지 않는다. VM 상태 감지·제어는 과금 시스템이 수행하지 않는다.
- 한 이벤트의 세 레코드는 자원·리전·구간이 같고 각 `Meter`가 한 번씩 존재한다.
- 모든 수량은 `UInt64` 범위의 정수(`0`~`18446744073709551615`)다. Compute는 60이며 Storage·Network는 0도 허용한다.
- `time`과 `ChargePeriodStart/End`는 밀리초까지 표현한다. 소수 초 4자리 이상은 끝자리가 0이어도 허용하지 않으며, 적재 시 반올림·절삭하지 않는다.
- 같은 인스턴스의 새 사용 구간은 중첩하지 않는다. 재전송은 같은 식별자와 내용을 유지한다.
- 단가·통화·금액·회사·SKU와 수신 시각은 입력에 포함하지 않는다.

위 규칙은 발생기와 계약 테스트에서 보장한다. 수집 단계에서는 입력을 신뢰하며 별도 내용 검증·자동 격리 경로를 두지 않는다. 같은 논리 레코드의 중복 반영은 ClickHouse 측에서 방어한다.

수량 상한·시간 정밀도는 저장 자료형에 맞춘 **프로젝트 제약**이며 CloudEvents 표준의 제한은 아니다. Java에서 수량 전체 범위를 다룰 때는 `long`이 아닌 `BigInteger` 등 정확한 정수 표현이 필요하다.

## 6. 수신 기준

- 발생기는 [요구사항의 운영자 통제 전제](requirements.md#6-제약사항)를 따른다. 고객이 수정 가능한 VM 내부 프로그램을 신뢰하는 구조는 아니다.
- 발생기는 Kafka에 직접 발행하고 VM `source`를 key로 사용한다. 브로커 내구성 ACK를 수신 성공으로 본다. HTTP `202` 계약은 ADR-010으로 대체한다.
- VM별 토픽을 만들지 않고 사용량 공유 토픽 하나를 사용한다. 외부에서 확정한 점유 시작·종료 사실의 토픽은 별도로 유지한다. 이는 내부 VM 제어 명령 토픽이 아니며 파티션 수는 이 결정과 구분한다.
- 브로커 인증·쓰기 권한은 유지한다. source의 정확성은 운영자 관리 발생기를 신뢰하며, ACL·key·payload가 출처를 증명한다고 보지 않는다.
- 발생기는 ACK 전 미확인 이벤트를 보존·재전송한다. Kafka ACK는 내구 기록이며 내용 검증 성공을 뜻하지 않는다.
- ClickHouse는 파싱·변환·저장하고 중복 반영을 방어한다. 파싱·저장 실패는 자동 폐기하지 않고 소비 실패로 남긴다. 검증 결과와 한계는 [수집 구현·검증](ingestion-implementation.md)을 따른다.

직접 발행 발생기는 `apps/usage-generator`다. 완료된 점유 구간을 명시해 실행하며 잔여 60초 미만은 발행하지 않는다. 재실행할 때 source·자원·시작 시각·수량 설정을 바꾸지 않는다. 접수 API·적재기·이전 파서는 `legacy/`로 이동하고 빌드에서 제외했다. deprecated HTTP OpenAPI는 현재 경로가 아니다.
