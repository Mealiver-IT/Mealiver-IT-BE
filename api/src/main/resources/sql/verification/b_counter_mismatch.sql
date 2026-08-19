-- (b) 이력 테이블과 캠페인 카운터가 일치하는가
-- 설계 근거: docs/planning/05_시스템설계.txt 1.1절 (b)
-- 결과: 0 rows 여야 함. Redis 카운터 전략이면 stock:{campaignId}:remaining 값도 별도 스크립트로 비교
--
-- 주의: LEFT JOIN 필수. INNER JOIN으로 쓰면 발급 이력이 0건인 캠페인이 조인에서 탈락해
-- 검증 대상에서 통째로 빠진다 (01_설계보완_검토안.txt 0절 #2 참고).

SELECT * FROM (
    SELECT c.id AS campaign_id,
           c.total_stock - c.remaining_stock AS counter_issued,
           COALESCE(actual.issued_count, 0) AS issued_count,
           c.remaining_stock AS remaining_stock,
           c.total_stock - COALESCE(actual.issued_count, 0) AS expected_remaining
    FROM campaign c
    LEFT JOIN (
        SELECT campaign_id, COUNT(*) AS issued_count
        FROM coupon_issue
        GROUP BY campaign_id
    ) actual ON actual.campaign_id = c.id
) t
WHERE t.counter_issued <> t.issued_count
-- ORDER BY t.campaign_id;
