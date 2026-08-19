-- (a) 캠페인별 발급 수량이 재고를 초과하지 않았는가
-- 설계 근거: docs/planning/05_시스템설계.txt 1.1절 (a)
-- 결과: 0 rows 여야 함

-- ORDER BY c.id;

SELECT c.id AS campaign_id, c.total_stock,
       COUNT(ci.id) AS issued_count,
       COUNT(ci.id) - c.total_stock AS over_count
FROM campaign c
LEFT JOIN coupon_issue ci ON ci.campaign_id = c.id
GROUP BY c.id, c.total_stock
HAVING COUNT(ci.id) > c.total_stock;

