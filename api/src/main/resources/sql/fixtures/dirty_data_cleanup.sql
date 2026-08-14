-- ============================================================================
-- dirty_data_seed.sql이 심은 700건 + 부대 데이터 정리
-- unique 제약(idempotency_key, coupon_code, request_id, login_id) 때문에
-- seed 스크립트를 재실행하기 전엔 반드시 이걸 먼저 돌릴 것.
-- FK 순서: coupon_state_log → coupon_issue → campaign / users
-- ============================================================================

DELETE l FROM coupon_state_log l
JOIN coupon_issue ci ON ci.id = l.coupon_issue_id
JOIN campaign c ON c.id = ci.campaign_id
WHERE c.name LIKE 'DIRTY\_%';

DELETE ci FROM coupon_issue ci
JOIN campaign c ON c.id = ci.campaign_id
WHERE c.name LIKE 'DIRTY\_%';

DELETE FROM campaign WHERE name LIKE 'DIRTY\_%';

DELETE FROM users WHERE login_id LIKE 'dirty\_user\_%';
