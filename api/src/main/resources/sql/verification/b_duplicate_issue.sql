-- (b) 유저당 캠페인별 중복 발급이 없는가
-- 설계 근거: docs/planning/05_시스템설계.txt 1.1절 (b)
-- 결과: 0 rows 여야 함 (uk_campaign_user 로 원천 불가하지만, 제약 우회 경로 유무를 이중 확인)

SELECT campaign_id, user_id, COUNT(*) AS cnt
FROM coupon_issue
GROUP BY campaign_id, user_id
HAVING COUNT(*) > 1
ORDER BY campaign_id, user_id;
