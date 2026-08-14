-- (c) 상태전이가 유효한가
-- 설계 근거: docs/planning/05_시스템설계.txt 1.1절 (c)
-- 3개 쿼리 모두 결과 0 rows 여야 함

-- c-1) 현재 상태로 이어지는 로그가 하나도 없는 레코드 전체 탐지
SELECT ci.id, ci.status
FROM coupon_issue ci
WHERE ci.status <> 'ISSUED'  -- ISSUED는 최초상태이므로 로그 없어도 정상
  AND NOT EXISTS (
      SELECT 1 FROM coupon_state_log l
      WHERE l.coupon_issue_id = ci.id AND l.to_status = ci.status
  );