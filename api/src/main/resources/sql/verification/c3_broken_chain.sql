-- c-3) 로그 체인이 끊기지 않는가 (직전 로그의 to_status == 다음 로그의 from_status)
-- 위 두 쿼리는 "각 로그가 개별적으로 유효한가"만 보므로,
-- ISSUED→USED 다음에 ISSUED→CANCELED 가 기록된 케이스(각각은 유효하지만 이어붙이면 모순)를 못 잡는다.
SELECT id, coupon_issue_id, from_status, to_status, prev_to_status
FROM (
    SELECT l.id, l.coupon_issue_id, l.from_status, l.to_status,
           LAG(l.to_status) OVER (PARTITION BY l.coupon_issue_id ORDER BY l.id) AS prev_to_status
    FROM coupon_state_log l
) t
WHERE (prev_to_status IS NULL     AND from_status <> 'ISSUED')  -- 첫 로그는 반드시 ISSUED에서 출발
   OR (prev_to_status IS NOT NULL AND from_status <> prev_to_status)
-- ORDER BY coupon_issue_id, id;
-- id는 AUTO_INCREMENT라 삽입 순서 = 전이 순서로 간주

