# ClickHouse Queries

원시 사용량은 회사 정보가 없으므로 BFF 비용 쿼리가 직접 읽지 않는다.

비용·사용량 상세 조회 SQL은 회사–VM 점유 이력으로 만든 귀속 조회 모델의 물리 형태를 확정한 뒤 추가한다. 현재 이 디렉터리에는 업무 조회 SQL이 없다.

이벤트 단위 원장·일반 뷰는 [`schema.sql`](../schema.sql)에 구현했고 [`schema_test.sql`](../schema_test.sql)로 중복·정밀도·가격 사본을 검사한다. 이 검사는 귀속·비용 계산 검증이 아니다. 작업별 근거는 [상태표](../../../docs/implementation-status.md)를 따른다.
