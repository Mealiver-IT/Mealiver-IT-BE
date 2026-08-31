-- 부하테스트/시연 준비 중 생긴 테스트용 캠페인들을 통째로 삭제한다.
-- reset_load_test_campaign.sql(재고만 복원, 캠페인은 유지 - 반복 테스트용)과 달리
-- 이건 캠페인 자체를 완전히 지운다 - 더 이상 재사용하지 않을 캠페인 정리용.
--
-- FK 순서: orders.coupon_issue_id 해제 -> coupon_state_log -> coupon_issue
-- -> campaign_stock_shard -> coupon -> campaign. coupon도 campaign을 참조하므로
-- reset 스크립트에는 없던 단계다.
--
-- 사용법: :campaign_ids를 실제 캠페인 id 목록(콤마 구분)으로 치환한 뒤 실행
--   sed 's/:campaign_ids/17,18,19/g' delete_test_campaigns.sql | mysql -h <host> -u <user> -p <db>

-- 삭제 전 확인 (지우려는 게 실제로 이 목록뿐인지 눈으로 검증)
SELECT id, name, status, total_stock, remaining_stock FROM campaign WHERE id IN (:campaign_ids);

-- 1) 주문이 이 캠페인들의 발급이력을 참조하고 있다면 연결 해제 (실제 테스트 캠페인은 보통 없지만 안전장치)
UPDATE orders o
JOIN coupon_issue ci ON ci.id = o.coupon_issue_id
SET o.coupon_issue_id = NULL
WHERE ci.campaign_id IN (:campaign_ids);

-- 2) 상태전이 로그 삭제
DELETE l FROM coupon_state_log l
JOIN coupon_issue ci ON ci.id = l.coupon_issue_id
WHERE ci.campaign_id IN (:campaign_ids);

-- 3) 발급이력 삭제
DELETE FROM coupon_issue WHERE campaign_id IN (:campaign_ids);

-- 4) 재고 샤딩 행 삭제
DELETE FROM campaign_stock_shard WHERE campaign_id IN (:campaign_ids);

-- 5) 쿠폰 정책 삭제 (campaign을 참조하므로 campaign보다 먼저)
DELETE FROM coupon WHERE campaign_id IN (:campaign_ids);

-- 6) 캠페인 자체 삭제
DELETE FROM campaign WHERE id IN (:campaign_ids);

-- 삭제 후 확인 - 0 rows여야 정상
SELECT id, name FROM campaign WHERE id IN (:campaign_ids);
