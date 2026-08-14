# API Contract v1

## 1. 목적

대시보드가 예상 비용·확정 정산·원본 사용량을 조회하고, Admin이 소속 회사의 과금 역할을 관리하는 계약을 정의한다.

상세 요청·응답은 [OpenAPI](../contracts/openapi.yaml)를 기준으로 한다.

## 2. API

| 영역 | Method | Endpoint | 기능 |
|---|---|---|---|
| 인증 | `POST` | `/api/v1/session` | 로그인 |
| 인증 | `DELETE` | `/api/v1/session` | 로그아웃 |
| 인증 | `GET` | `/api/v1/me` | 현재 사용자·회사·역할 조회 |
| 비용 | `GET` | `/api/v1/costs` | 최대 31일의 예상 비용 필터·그룹화·정렬 |
| 정산 | `GET` | `/api/v1/settlements/{billingMonth}` | 월간 확정 금액 조회 |
| 사용량 | `GET` | `/api/v1/usage-records` | 원본 사용량 상세 조회 |
| 권한 | `GET` | `/api/v1/billing-members` | 과금 구성원과 역할 조회 |
| 권한 | `PUT` | `/api/v1/billing-members/{userId}/role` | 역할 부여·변경 |
| 권한 | `DELETE` | `/api/v1/billing-members/{userId}/role` | 과금 역할 제거 |
| 수집 | `POST` | `/api/v1/usage-events` | 사용량 이벤트 검증·내구성 기록 |

## 3. 비용 조회

`GET /api/v1/costs` 하나가 화면 기획에 따라 총액·추이·구성·Top N을 만든다.

- `from`, `to`: 시작 포함·종료 제외, 최대 31일
- `serviceCategory`, `resourceId`: 선택 필터
- `groupBy`: `day`, `service`, `resource` 중 최대 2개
- `sort`, `direction`, `limit`: 정렬과 결과 제한
- 사용량 정렬은 단위가 하나로 제한되는 조회에서만 허용한다.
- 응답은 요청 기간과 실제 반영 기준인 `dataAsOf`를 구분한다.

집계 결과는 제한된 크기로 반환한다. 원본 사용량만 `(ChargePeriodEnd, source, id)` 기준의 불투명한 커서로 페이지네이션한다.

## 4. 역할별 권한

| 기능 | 비로그인 | Viewer | Admin | 인증된 발생기 |
|---|---:|---:|---:|---:|
| 로그인 | 허용 | 허용 | 허용 | 거부 |
| 본인 정보·로그아웃 | 거부 | 허용 | 허용 | 거부 |
| 비용·정산·원본 조회 | 거부 | 허용 | 허용 | 거부 |
| 구성원·역할 조회 | 거부 | 거부 | 허용 | 거부 |
| 역할 변경·제거 | 거부 | 거부 | 허용 | 거부 |
| 이벤트 발행 | 거부 | 거부 | 거부 | 허용 |

## 5. 보안과 오류

- 사용자 API는 `tenantId`를 받지 않고 현재 세션의 회사 범위를 강제한다.
- 다른 회사의 자원과 사용자는 존재하지 않는 자원처럼 `404`로 처리한다.
- 역할 부족은 `403`, 미인증은 `401`로 처리한다.
- 역할 충돌과 마지막 Admin 제거는 `409`로 처리한다.
- 이벤트 형식 오류는 `400`, 사용량 규칙 위반은 `422`, Kafka 내구성 기록 실패는 `503`으로 처리한다.
- 상태 변경 요청은 CSRF 토큰을 요구한다.
- 커서는 내용을 노출하지 않고 변조를 검증한다.
- 응답에는 내부 URL·SQL·테이블·Kafka topic/partition/offset·스택 트레이스를 포함하지 않는다.
- 오류 응답은 고정 코드와 `traceId`만 제공한다.

발생기는 사용자 세션과 분리된 불투명한 Bearer 자격 증명을 사용한다. 자격 증명의 해시와 상태는 PostgreSQL에 저장하며 발급·회전 방식은 수집 구현 전에 결정한다.
