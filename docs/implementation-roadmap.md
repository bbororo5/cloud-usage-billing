# Project Roadmap and Session Handoff

## 1. 목표와 진행 원칙

클라우드 사용량 이벤트를 수집해 현재 월 예상 비용과 월간 확정 금액을 제공하는 Java 21 포트폴리오다. 데이터 파이프라인, 배치, PostgreSQL·ClickHouse, 멀티테넌시·RBAC 역량을 보여주되 불필요한 도메인 복잡도는 추가하지 않는다.

- 설계 → 학습 → 이해 확인 → 사용자 승인 → 구현 → 테스트·커밋·푸시 순서를 지킨다.
- 사용자가 핵심 원리와 선택 근거를 자기 말로 정확히 설명하기 전에는 구현하지 않는다.
- ADR과 계약 문서가 구현보다 우선한다. 기존 코드는 현재 설계를 보장하지 않는다.
- 선집계·Redis·추가 서비스·물리적 셀 분리는 측정 또는 실제 요구가 생길 때만 도입한다.

## 2. 현재 위치

**단계 2의 설계 동기화 및 학습 시작 전**이다. 실행 기반과 초기 수집 코드는 존재하지만, 최근 확정한 과금 비인지 입력 모델보다 이전에 구현된 부분이 있다. 따라서 현 구현을 다음 단계의 정답으로 확장하지 않는다.

현재 확정된 핵심은 다음과 같다.

- VM은 1분 주기의 CloudEvents 사용량과 `source`만 발행한다. 회사·과금 계정·Kafka·가격 정보는 모른다.
- Kafka 키는 VM 출처인 `source`다. 원시 사용량은 ClickHouse에 보존한다.
- PostgreSQL의 회사–VM 점유 이력은 `[validFrom, validTo)`로 사용 시점의 지불 회사를 결정한다.
- 귀속하지 못했거나 중복 귀속된 사용량은 공개·확정하지 않고 오류로 격리한다.
- BFF는 원시 사용량을 읽지 못한다. 회사 귀속이 완료된 조회 모델만 읽는다.
- ClickHouse는 테넌트별 읽기 신원과 Row Policy로 조회 범위를 한 번 더 강제한다.
- 현재는 단일 셀이다. 대형·고보안 고객을 위한 셀·전용 클러스터 분리는 미래 확장 경로이며 지금 구현하지 않는다.

근거: [ADR-006](adr/0006-tenant-rbac-enforcement.md), [ADR-008](adr/0008-tenant-neutral-usage-ingestion.md)

## 3. 바로 다음 작업

### 2-A. 설계 동기화

아래 문서를 ADR-006·008과 일치시킨다. 먼저 변경 초안을 검토·승인받고, 이 단계에서는 코드를 수정하지 않는다.

1. `event-contract.md` — 입력에서 과금·회사 문맥 제거, `source` 중심 계약 명시
2. `logical-data-model.md`, `clickhouse-physical-data-model.md` — 원시 사용량, 점유 이력, 귀속 조회 모델과 귀속 오류 경계 명시
3. `storage-access-contract.md`, `architecture.md` — 원시 접근 차단과 BFF 조회 경로 명시
4. `architecture-test-plan.md` — 시간 점유, 미귀속·중복 귀속, 원시 접근 거부, 다른 회사 행 차단 검증 추가

### 2-B. 수집 파이프라인 학습

구현 전 관점에서 다음을 학습하고 이해 확인을 한다.

```text
발생기 → 수신 검증 → Kafka의 내구성 있는 기록 → 원장 적재기 → 원시 ClickHouse 원장
```

핵심 주제는 CloudEvents 계약, Kafka 키와 파티션 순서, 내구성 있는 수신 성공, at-least-once와 중복 제어, 적재 중단 후 재처리다.

### 2-C. 수집 파이프라인 정합화 구현

2-B를 승인한 뒤에만 기존 수집 코드를 새 계약에 맞춘다. 정상·중복·재시작·적재 중단 테스트를 통과하고 하나의 작업 단위로 커밋·푸시한다.

## 4. 장기 로드맵

| 단계 | 상태 | 산출물 | 완료 기준 |
|---|---|---|---|
| 0. 설계 기반 | 승인, 2-A 동기화 진행 전 | 요구사항·품질 시나리오·논리 모델·ADR·계약 | ADR와 하위 문서가 일치 |
| 1. 실행 기반 | 완료 | Java 21, 로컬 PostgreSQL·Kafka·ClickHouse, 모듈 구조 | 로컬 기동과 스키마 적용 |
| 2. 테넌트 비인지 수집 | 진행 예정 | 발생기·수신·Kafka·원시 원장 | 중복·재시작에도 수신한 유효 이벤트 유실 0건 |
| 3. 귀속·인증·권한 | 대기 | 점유 이력, 귀속 조회 모델, 세션, RBAC, RLS·Row Policy | 다른 회사 노출 0건, 귀속 오류는 확정 차단 |
| 4. 비용 조회 | 대기 | 기간·자원 조건의 예상/확정 비용 API | 계약·작은 데이터 정확성 테스트 통과 |
| 5. 월간 정산 | 대기 | 회사·월 단위 검증, 재시도, 확정 | 중단 후 재실행 차이 0원 |
| 6. 웹 대시보드 | 대기 | 현재 월·월간 확정·근거·권한 화면 | Viewer·Admin 핵심 흐름 시연 |
| 7. 성능·장애 검증 | 대기 | k6 부하, 적체·DB 장애 실험, 측정 보고서 | 품질 시나리오 결과와 개선 근거 기록 |

## 5. 구현 단위

```text
usage-generator       : 정상·중복·지연·부하 이벤트 발생
usage-event-api       : 계약 검증 후 Kafka 기록
usage-ledger-writer   : Kafka 소비 후 원시 ClickHouse 원장 적재
attribution-worker    : 점유 이력으로 사용량을 회사에 귀속하고 오류 격리
settlement-batch      : 월간 검증·재시도·확정
billing-bff           : 세션·RBAC·비용·정산 API
billing-web           : 대시보드
```

`attribution-worker`의 구체적 실행 방식과 귀속 조회 모델의 물리 형태는 단계 3 학습·설계에서 결정한다. 지금 확정된 것은 책임과 접근 경계뿐이다.

## 6. 미결정 사항

- 귀속 조회 모델을 ClickHouse의 어떤 물리 구조로 만들지
- 테넌트별 ClickHouse 신원·자격증명을 생성·회전·선택하는 구체적 방식
- 셀 분리를 실제로 시작하는 부하·보안 기준
- 요청 시 집계가 한계에 도달했을 때 선집계를 추가하는 기준

이 항목은 현재 요구사항으로 미리 기술을 고정하지 않는다.

## 7. 새 세션 시작 프롬프트

아래 내용을 새 LLM 세션의 첫 메시지로 사용한다.

```text
나는 cloud-usage-billing Java 21 포트폴리오를 이어서 진행한다.
저장소: https://github.com/bbororo5/cloud-usage-billing

먼저 docs/implementation-roadmap.md를 읽고, 이어서 docs/adr/0006-tenant-rbac-enforcement.md와 docs/adr/0008-tenant-neutral-usage-ingestion.md를 읽어라. 이 문서들이 현재 설계의 기준이다.

진행 원칙:
- 바로 구현하거나 기술을 추가로 확정하지 말 것.
- 설명은 추상 구조를 먼저 제시하고, 최대 세 덩어리로 나눌 것.
- 내가 이해한 내용을 내 말로 설명하고 승인하기 전까지 코드를 수정하지 말 것.
- 기존 코드는 이전 입력 모델을 포함할 수 있으므로, ADR보다 우선하지 않는다.
- 문서·코드 변경은 작은 작업 단위로 커밋하고 푸시할 것.

현재 작업은 로드맵 2-A, 즉 ADR-006·008과 하위 설계 문서의 동기화다. 먼저 event-contract.md, logical-data-model.md, clickhouse-physical-data-model.md, storage-access-contract.md, architecture.md, architecture-test-plan.md에서 ADR와 충돌하는 부분을 짧은 표로 찾아라. 코드 변경은 하지 말고, 문서 변경 초안과 판단 근거만 제시해 내 승인을 받아라.
```
