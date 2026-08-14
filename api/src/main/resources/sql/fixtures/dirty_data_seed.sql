-- ============================================================================
-- 오염 데이터 시더 — 검증쿼리 5종(7개 파일) x 100건 = 700건
-- 설계 근거: docs/planning/05_시스템설계.txt 1.1절, api/src/main/resources/sql/verification/README.md
--
-- 각 케이스는 서로 다른 전용 캠페인/유저를 써서 "다른 케이스의 검증쿼리에는 안 잡히도록"
-- 격리했다. 실행 후 verification/ 폴더의 쿼리를 돌리면 케이스당 정확히 아래 결과가 나와야 한다.
--
--   a_stock_overissue.sql        → 1 row  (campaign_id=DIRTY_A, over_count=100)
--   b_counter_mismatch.sql       → 100 rows (DIRTY_B_001~100, 캠페인당 1건씩)
--   c1_missing_log.sql           → 100 rows (DIRTY_C1 소속 발급 100건)
--   c2_invalid_transition.sql    → 100 rows (DIRTY_C2 소속 로그 100건)
--   c3_broken_chain.sql          → 100 rows (DIRTY_C3 소속 로그 100건)
--   d_tier_violation.sql         → 100 rows (DIRTY_D 소속 발급 100건)
--   e_tier_orders_mismatch.sql   → 100 rows (dirty_user_e_001~100)
--
-- 재실행하려면 먼저 dirty_data_cleanup.sql을 실행할 것 (unique 제약 때문에 중복 실행 시 에러).
-- MySQL 8.0 기준 (재귀 CTE 사용).
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 0. 공용 더미 유저 100명 (케이스 A, B, C1, C2, C3, D가 공유)
--    캠페인이 케이스별로 분리되어 있어 같은 유저를 여러 캠페인에 재사용해도
--    uk_campaign_user(campaign_id, user_id)에 안 걸린다.
-- ----------------------------------------------------------------------------
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO users (login_id, name, phone, email, membership_tier, tier_calculated_at, created_at)
SELECT
    CONCAT('dirty_user_', LPAD(n, 3, '0')),
    CONCAT('오염테스트유저', n),
    CONCAT('010-0000-', LPAD(n, 4, '0')),
    CONCAT('dirty_user_', LPAD(n, 3, '0'), '@test.local'),
    'PRIVATE',
    NULL,
    NOW()
FROM seq;


-- ============================================================================
-- CASE A — 초과발급 (a_stock_overissue.sql)
-- 재고 0인 캠페인에 100건을 발급. remaining_stock을 -100으로 맞춰
-- (총 재고-남은재고 = 100 = 실제 발급건수) 카운터 자체는 일치시켜서
-- b_counter_mismatch.sql은 안 잡히게 격리했다.
-- ============================================================================
INSERT INTO campaign (name, total_stock, remaining_stock, open_at, close_at, status, min_membership_tier, version)
VALUES ('DIRTY_A_초과발급', 0, -100, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 HOUR), 'CLOSED', NULL, 0);

WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO coupon_issue (
    campaign_id, user_id, coupon_code, discount_type, discount_value, max_discount_amount,
    issued_membership_tier, status, idempotency_key, issued_at, valid_until, version, created_at, updated_at
)
SELECT
    (SELECT id FROM campaign WHERE name = 'DIRTY_A_초과발급'),
    u.id,
    CONCAT('DIRTY-A-CC-', LPAD(seq.n, 3, '0')),
    'RATE', 0.1000, NULL,
    'PRIVATE', 'ISSUED',
    CONCAT('DIRTY-A-IK-', LPAD(seq.n, 3, '0')),
    NOW(), DATE_ADD(NOW(), INTERVAL 24 HOUR),
    0, NOW(), NOW()
FROM seq
JOIN users u ON u.login_id = CONCAT('dirty_user_', LPAD(seq.n, 3, '0'));


-- ============================================================================
-- CASE B — 카운터불일치 (b_counter_mismatch.sql)
-- 캠페인 100개를 만들어 각각 total_stock=10/remaining_stock=10(카운터는 "0건 발급"이라
-- 주장)으로 세팅해두고, 실제로는 1건씩만 발급해 캠페인당 정확히 1건의 불일치를 낸다.
-- (총 100개 캠페인 = 검증쿼리 결과 100 rows)
-- ----------------------------------------------------------------------------
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO campaign (name, total_stock, remaining_stock, open_at, close_at, status, min_membership_tier, version)
SELECT
    CONCAT('DIRTY_B_', LPAD(n, 3, '0')),
    10, 10,
    DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 HOUR),
    'CLOSED', NULL, 0
FROM seq;

WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO coupon_issue (
    campaign_id, user_id, coupon_code, discount_type, discount_value, max_discount_amount,
    issued_membership_tier, status, idempotency_key, issued_at, valid_until, version, created_at, updated_at
)
SELECT
    c.id,
    u.id,
    CONCAT('DIRTY-B-CC-', LPAD(seq.n, 3, '0')),
    'RATE', 0.1000, NULL,
    'PRIVATE', 'ISSUED',
    CONCAT('DIRTY-B-IK-', LPAD(seq.n, 3, '0')),
    NOW(), DATE_ADD(NOW(), INTERVAL 24 HOUR),
    0, NOW(), NOW()
FROM seq
JOIN campaign c ON c.name = CONCAT('DIRTY_B_', LPAD(seq.n, 3, '0'))
JOIN users u ON u.login_id = CONCAT('dirty_user_', LPAD(seq.n, 3, '0'));


-- ============================================================================
-- CASE C1 — 로그누락 (c1_missing_log.sql)
-- status='USED'로 100건 발급해두고 coupon_state_log는 하나도 안 남긴다.
-- ============================================================================
INSERT INTO campaign (name, total_stock, remaining_stock, open_at, close_at, status, min_membership_tier, version)
VALUES ('DIRTY_C1_로그누락', 1000, 900, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 HOUR), 'CLOSED', NULL, 0);

WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO coupon_issue (
    campaign_id, user_id, coupon_code, discount_type, discount_value, max_discount_amount,
    issued_membership_tier, status, idempotency_key, issued_at, valid_until, used_at, version, created_at, updated_at
)
SELECT
    (SELECT id FROM campaign WHERE name = 'DIRTY_C1_로그누락'),
    u.id,
    CONCAT('DIRTY-C1-CC-', LPAD(seq.n, 3, '0')),
    'RATE', 0.1000, NULL,
    'PRIVATE', 'USED',
    CONCAT('DIRTY-C1-IK-', LPAD(seq.n, 3, '0')),
    NOW(), DATE_ADD(NOW(), INTERVAL 24 HOUR), NOW(),
    0, NOW(), NOW()
FROM seq
JOIN users u ON u.login_id = CONCAT('dirty_user_', LPAD(seq.n, 3, '0'));
-- coupon_state_log는 의도적으로 삽입하지 않음 — 이게 곧 "로그 누락" 오염 데이터.


-- ============================================================================
-- CASE C2 — 상태역행 (c2_invalid_transition.sql)
-- 개별 전이는 각각 유효(ISSUED|CANCELED, 그리고 나중 로그는 ci.status와 맞춤)해 보이지만
-- 화이트리스트에 없는 CANCELED|USED 전이를 심는다.
-- 체인은 로그1.to_status(CANCELED) == 로그2.from_status(CANCELED)로 이어지게 해서
-- c3_broken_chain.sql은 안 잡히도록 격리했다.
-- ============================================================================
INSERT INTO campaign (name, total_stock, remaining_stock, open_at, close_at, status, min_membership_tier, version)
VALUES ('DIRTY_C2_상태역행', 1000, 900, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 HOUR), 'CLOSED', NULL, 0);

WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO coupon_issue (
    campaign_id, user_id, coupon_code, discount_type, discount_value, max_discount_amount,
    issued_membership_tier, status, idempotency_key, issued_at, valid_until, used_at, version, created_at, updated_at
)
SELECT
    (SELECT id FROM campaign WHERE name = 'DIRTY_C2_상태역행'),
    u.id,
    CONCAT('DIRTY-C2-CC-', LPAD(seq.n, 3, '0')),
    'RATE', 0.1000, NULL,
    'PRIVATE', 'USED',
    CONCAT('DIRTY-C2-IK-', LPAD(seq.n, 3, '0')),
    NOW(), DATE_ADD(NOW(), INTERVAL 24 HOUR), NOW(),
    0, NOW(), NOW()
FROM seq
JOIN users u ON u.login_id = CONCAT('dirty_user_', LPAD(seq.n, 3, '0'));

-- 로그 1/2 (id 순서가 전이 순서로 간주되므로 반드시 두 번의 INSERT로 나눈다)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO coupon_state_log (coupon_issue_id, from_status, to_status, request_id, created_at)
SELECT ci.id, 'ISSUED', 'CANCELED', CONCAT('DIRTY-C2-REQ1-', LPAD(seq.n, 3, '0')), NOW()
FROM seq
JOIN coupon_issue ci ON ci.idempotency_key = CONCAT('DIRTY-C2-IK-', LPAD(seq.n, 3, '0'));

WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO coupon_state_log (coupon_issue_id, from_status, to_status, request_id, created_at)
SELECT ci.id, 'CANCELED', 'USED', CONCAT('DIRTY-C2-REQ2-', LPAD(seq.n, 3, '0')), NOW()
FROM seq
JOIN coupon_issue ci ON ci.idempotency_key = CONCAT('DIRTY-C2-IK-', LPAD(seq.n, 3, '0'));
-- CANCELED|USED 는 화이트리스트에 없음 → c2에서 정확히 100건 탐지.
-- 체인(CANCELED→CANCELED)은 이어지므로 c3는 안 잡음. to_status=USED가 ci.status와 일치하므로 c1도 안 잡음.


-- ============================================================================
-- CASE C3 — 체인단절 (c3_broken_chain.sql)
-- 개별로는 둘 다 화이트리스트에 있는 전이(ISSUED→USED, ISSUED→CANCELED)지만
-- 같은 발급건에 대해 둘 다 ISSUED에서 출발한 것처럼 기록해 체인이 끊기게 한다.
-- (c3_broken_chain.sql 파일 주석에 있는 예시를 그대로 재현)
-- ============================================================================
INSERT INTO campaign (name, total_stock, remaining_stock, open_at, close_at, status, min_membership_tier, version)
VALUES ('DIRTY_C3_체인단절', 1000, 900, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 HOUR), 'CLOSED', NULL, 0);

WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO coupon_issue (
    campaign_id, user_id, coupon_code, discount_type, discount_value, max_discount_amount,
    issued_membership_tier, status, idempotency_key, issued_at, valid_until, canceled_at, version, created_at, updated_at
)
SELECT
    (SELECT id FROM campaign WHERE name = 'DIRTY_C3_체인단절'),
    u.id,
    CONCAT('DIRTY-C3-CC-', LPAD(seq.n, 3, '0')),
    'RATE', 0.1000, NULL,
    'PRIVATE', 'CANCELED',
    CONCAT('DIRTY-C3-IK-', LPAD(seq.n, 3, '0')),
    NOW(), DATE_ADD(NOW(), INTERVAL 24 HOUR), NOW(),
    0, NOW(), NOW()
FROM seq
JOIN users u ON u.login_id = CONCAT('dirty_user_', LPAD(seq.n, 3, '0'));

WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO coupon_state_log (coupon_issue_id, from_status, to_status, request_id, created_at)
SELECT ci.id, 'ISSUED', 'USED', CONCAT('DIRTY-C3-REQ1-', LPAD(seq.n, 3, '0')), NOW()
FROM seq
JOIN coupon_issue ci ON ci.idempotency_key = CONCAT('DIRTY-C3-IK-', LPAD(seq.n, 3, '0'));

WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO coupon_state_log (coupon_issue_id, from_status, to_status, request_id, created_at)
SELECT ci.id, 'ISSUED', 'CANCELED', CONCAT('DIRTY-C3-REQ2-', LPAD(seq.n, 3, '0')), NOW()
FROM seq
JOIN coupon_issue ci ON ci.idempotency_key = CONCAT('DIRTY-C3-IK-', LPAD(seq.n, 3, '0'));
-- 로그2.from_status(ISSUED) != 로그1.to_status(USED) → c3에서 정확히 100건 탐지.
-- 각 전이 자체는 화이트리스트에 있으므로 c2는 안 잡음. to_status=CANCELED가 ci.status와 일치하므로 c1도 안 잡음.


-- ============================================================================
-- CASE D — 등급위반 (d_tier_violation.sql)
-- 캠페인 최소 등급을 SERGEANT(병장)로 걸어두고, 발급 시점 스냅샷 등급은 PRIVATE(이등병)로
-- 100건 발급한다. users.membership_tier는 안 건드리므로 다른 케이스와 무관.
-- ============================================================================
INSERT INTO campaign (name, total_stock, remaining_stock, open_at, close_at, status, min_membership_tier, version)
VALUES ('DIRTY_D_등급위반', 1000, 900, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 HOUR), 'CLOSED', 'SERGEANT', 0);

WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO coupon_issue (
    campaign_id, user_id, coupon_code, discount_type, discount_value, max_discount_amount,
    issued_membership_tier, status, idempotency_key, issued_at, valid_until, version, created_at, updated_at
)
SELECT
    (SELECT id FROM campaign WHERE name = 'DIRTY_D_등급위반'),
    u.id,
    CONCAT('DIRTY-D-CC-', LPAD(seq.n, 3, '0')),
    'RATE', 0.5000, NULL,
    'PRIVATE', 'ISSUED',
    CONCAT('DIRTY-D-IK-', LPAD(seq.n, 3, '0')),
    NOW(), DATE_ADD(NOW(), INTERVAL 24 HOUR),
    0, NOW(), NOW()
FROM seq
JOIN users u ON u.login_id = CONCAT('dirty_user_', LPAD(seq.n, 3, '0'));


-- ============================================================================
-- CASE E — 등급불일치 (e_tier_orders_mismatch.sql)
-- 전용 유저 100명을 membership_tier='SERGEANT'로 만들어두고 orders는 하나도 안 남긴다.
-- 어느 월 구간을 잡아도 주문 0건이므로 expected_tier='PRIVATE'와 항상 어긋난다.
-- (공용 유저 풀을 안 건드려서 다른 케이스와 완전히 격리)
-- ============================================================================
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
)
INSERT INTO users (login_id, name, phone, email, membership_tier, tier_calculated_at, created_at)
SELECT
    CONCAT('dirty_user_e_', LPAD(n, 3, '0')),
    CONCAT('오염테스트유저E', n),
    CONCAT('010-0001-', LPAD(n, 4, '0')),
    CONCAT('dirty_user_e_', LPAD(n, 3, '0'), '@test.local'),
    'SERGEANT',
    NOW(),
    NOW()
FROM seq;
-- orders는 의도적으로 삽입하지 않음 — 이게 곧 "등급-주문 불일치" 오염 데이터.
-- e_tier_orders_mismatch.sql 실행 시 :월시작/:월종료를 아무 달로 잡아도 이 100명은 걸린다.
