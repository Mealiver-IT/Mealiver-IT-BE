-- (d) 상태전이가 유효한가
-- 설계 근거: docs/planning/05_시스템설계.txt 1.1절 (d)
-- 3개 쿼리 모두 결과 0 rows 여야 함

-- d-1) 현재 상태로 이어지는 로그가 하나도 없는 레코드 전체 탐지
SELECT ci.id, ci.status
FROM coupon_issue ci
WHERE ci.status <> 'ISSUED'  -- ISSUED는 최초상태이므로 로그 없어도 정상
  AND NOT EXISTS (
      SELECT 1 FROM coupon_state_log l
      WHERE l.coupon_issue_id = ci.id AND l.to_status = ci.status
  );

-- d-2) 허용되지 않은 전이가 로그에 기록된 적 있는지 (상태머신 우회 버그 탐지)
SELECT * FROM coupon_state_log
WHERE (from_status, to_status) NOT IN (
    ('ISSUED','USED'), ('ISSUED','CANCELED'), ('ISSUED','EXPIRED'), ('USED','CANCELED')
)
ORDER BY id;

-- d-3) 로그 체인이 끊기지 않는가 (직전 로그의 to_status == 다음 로그의 from_status)
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
ORDER BY coupon_issue_id, id;
-- id는 AUTO_INCREMENT라 삽입 순서 = 전이 순서로 간주
