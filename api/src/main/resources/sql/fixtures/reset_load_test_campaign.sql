-- 부하테스트 캠페인 리셋 — 반복 실행(V1~V4 버전 비교, 각 5회) 전 초기화용.
-- 설계 근거: docs/planning/03_버전사다리_실험설계.txt 6절 "매 실행 전 초기화" 1)/2).
-- 특정 campaign_id의 발급 이력(coupon_issue/coupon_state_log)을 지우고
-- remaining_stock을 total_stock으로 복원한다 — "재고는 있는데 카운터만 틀림" 같은
-- 카운터불일치 오염을 만들지 않으려면 숫자만 UPDATE하지 말고 반드시 이 3단계를 같이 해야 한다.
--
-- 이 스크립트 범위 밖(다른 담당 영역): Redis 카운터 키 삭제(3), 워밍업 실행(4),
-- k6 유저 목록 재사용(5) — 03_버전사다리 6절 원본 체크리스트 참고.
--
-- 사용법: :campaign_id를 실제 캠페인 id로 치환한 뒤 실행
--   sed 's/:campaign_id/20/g' reset_load_test_campaign.sql | mysql -h <host> -u <user> -p <db>

-- 리셋 전 스냅샷 (지워지기 전 상태 기록용 — 실제 발급건수와 카운터가 얼마나 어긋나 있었는지 확인)
SELECT
    c.id AS campaign_id, c.name, c.total_stock, c.remaining_stock,
    COUNT(ci.id) AS actual_issued_before_reset
FROM campaign c
LEFT JOIN coupon_issue ci ON ci.campaign_id = c.id
WHERE c.id = :campaign_id
GROUP BY c.id, c.name, c.total_stock, c.remaining_stock;

-- 1) 상태전이 로그 삭제 (FK 순서상 발급이력보다 먼저 지워야 함)
DELETE l FROM coupon_state_log l
JOIN coupon_issue ci ON ci.id = l.coupon_issue_id
WHERE ci.campaign_id = :campaign_id;

-- 2) 발급이력 삭제
DELETE FROM coupon_issue WHERE campaign_id = :campaign_id;

-- 3) 재고 카운터를 total_stock으로 복원 (total_stock 자체를 바꾸고 싶으면 이 UPDATE 전에 별도로)
UPDATE campaign SET remaining_stock = total_stock WHERE id = :campaign_id;

-- 리셋 후 확인 — actual_issued_after_reset=0, total_stock=remaining_stock이어야 정상
SELECT
    c.id AS campaign_id, c.total_stock, c.remaining_stock,
    COUNT(ci.id) AS actual_issued_after_reset
FROM campaign c
LEFT JOIN coupon_issue ci ON ci.campaign_id = c.id
WHERE c.id = :campaign_id
GROUP BY c.id, c.total_stock, c.remaining_stock;
