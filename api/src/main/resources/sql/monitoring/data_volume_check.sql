-- 데이터 볼륨 모니터링 — 정상 시드 캠페인 vs 벤치/리허설 캠페인 구분해서 확인.
-- 설계 근거: 윤태형 Phase 3 항목 "부하테스트 반복실행 후 누적된 실제 발급이력과
-- 시드 데이터 구분" (일정과역할.txt).
--
-- 구분 방식: campaign에 "이게 시드인지 벤치인지" 나타내는 컬럼이 따로 없어서,
-- 이름 패턴으로 구분하는 휴리스틱을 쓴다.
--   - DIRTY_ 로 시작 -> 오염 데이터 fixture (#43, dirty_data_seed.sql)
--   - 영문 소문자+숫자+하이픈만으로 구성(smoke-test-campaign,
--     phase1-rehearsal-100vs50, phase3-coupon-race-20k 등) -> 벤치/리허설
--   - 그 외(한글 이름 등) -> CampaignSeedRunner가 만든 정상 시드
-- 완벽한 구분은 아니니, 새 벤치 캠페인을 만들 때도 영문 소문자+하이픈 네이밍을
-- 지켜야 이 쿼리가 계속 맞는다.
--
-- 실행: mysql --default-character-set=utf8mb4 -h <host> -u <user> -p <db> < data_volume_check.sql
-- (한글 컬럼 별칭이 있어서 --default-character-set=utf8mb4 없으면 문법 에러 남)

-- 1) 시드 vs 벤치 vs 오염fixture 요약
SELECT
    CASE
        WHEN c.name LIKE 'DIRTY\_%' THEN '오염 fixture(테스트용)'
        WHEN c.name REGEXP '^[a-z0-9-]+$' THEN '벤치/리허설'
        ELSE '정상 시드'
    END AS 구분,
    COUNT(*) AS 캠페인_수,
    SUM(c.total_stock) AS 총_재고_합계,
    COALESCE(SUM(issued.cnt), 0) AS 총_발급건수
FROM campaign c
LEFT JOIN (
    SELECT campaign_id, COUNT(*) AS cnt FROM coupon_issue GROUP BY campaign_id
) issued ON issued.campaign_id = c.id
GROUP BY 구분
ORDER BY 구분;

-- 2) 벤치/리허설 캠페인 상세 — 캠페인별로 재고 대비 실제 발급건수, 카운터-실제 불일치 여부
SELECT
    c.id, c.name, c.status, c.total_stock, c.remaining_stock,
    COALESCE(issued.cnt, 0) AS actual_issued,
    c.total_stock - c.remaining_stock AS counter_issued,
    (c.total_stock - c.remaining_stock) - COALESCE(issued.cnt, 0) AS counter_diff
FROM campaign c
LEFT JOIN (
    SELECT campaign_id, COUNT(*) AS cnt FROM coupon_issue GROUP BY campaign_id
) issued ON issued.campaign_id = c.id
WHERE c.name REGEXP '^[a-z0-9-]+$'
ORDER BY c.id;

-- 3) 전체 규모 참고용 (100만 유저 / 300만 발급이력 목표 대비 현재)
SELECT
    (SELECT COUNT(*) FROM users) AS users_total,
    (SELECT COUNT(*) FROM orders) AS orders_total,
    (SELECT COUNT(*) FROM coupon_issue) AS coupon_issue_total,
    (SELECT COUNT(*) FROM coupon_state_log) AS coupon_state_log_total;
