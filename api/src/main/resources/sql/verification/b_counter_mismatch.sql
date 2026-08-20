-- (b) 이력 테이블과 캠페인 카운터가 일치하는가
-- 설계 근거: docs/planning/05_시스템설계.txt 1.1절 (b)
-- 결과: 0 rows 여야 함. Redis 카운터 전략이면 stock:{campaignId}:remaining 값도 별도 스크립트로 비교
--
-- 2026-08-20 재고 샤딩 도입: campaign.remaining_stock은 더 이상 reserve()가 직접 갱신하지 않고,
-- 발급 성공 이벤트 리스너/15초 재동기화 잡이 campaign_stock_shard 합계를 사후에 복사해두는
-- 값이다(CampaignStockSnapshotListener 참고) - 비동기 반영 지연 구간에 이 컬럼만 비교하면
-- 오탐(false mismatch)이 날 수 있다. 그래서 항상 최신인 샤드 합계를 우선 쓰고, 아직 샤드가
-- 지연 생성되지 않은 캠페인(예약 시도가 한 번도 없었던 캠페인)만 campaign.remaining_stock으로
-- 폴백한다 - 그 경우 remaining_stock은 total_stock과 같아 어차피 발급 0건과 일치한다.
--
-- 주의: LEFT JOIN 필수. INNER JOIN으로 쓰면 발급 이력이 0건인 캠페인이 조인에서 탈락해
-- 검증 대상에서 통째로 빠진다 (01_설계보완_검토안.txt 0절 #2 참고).

SELECT * FROM (
    SELECT c.id AS campaign_id,
           c.total_stock - COALESCE(shard.remaining_stock, c.remaining_stock) AS counter_issued,
           COALESCE(actual.issued_count, 0) AS issued_count,
           COALESCE(shard.remaining_stock, c.remaining_stock) AS remaining_stock,
           c.total_stock - COALESCE(actual.issued_count, 0) AS expected_remaining
    FROM campaign c
    LEFT JOIN (
        SELECT campaign_id, SUM(remaining_stock) AS remaining_stock
        FROM campaign_stock_shard
        GROUP BY campaign_id
    ) shard ON shard.campaign_id = c.id
    LEFT JOIN (
        SELECT campaign_id, COUNT(*) AS issued_count
        FROM coupon_issue
        GROUP BY campaign_id
    ) actual ON actual.campaign_id = c.id
) t
WHERE t.counter_issued <> t.issued_count
-- ORDER BY t.campaign_id;
