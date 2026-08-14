-- (e) 계급이 orders 집계와 일치하는가
-- 설계 근거: docs/planning/05_시스템설계.txt 1.1절 (e), 09_기획서.txt 6.2절, 04_아키텍처.txt 6.2절
-- 결과: 0 rows 여야 함.
--
-- :월시작 / :월종료 는 대상 배치 실행분의 tier_calculated_at이 속한 캘린더 월 경계로 고정
-- (NOW() 사용 금지 — 결정론성 규칙). 수동 실행 시 실제 날짜값으로 치환해서 사용할 것.
-- 예) SET @월시작 = '2026-08-01 00:00:00'; SET @월종료 = '2026-09-01 00:00:00';
--
-- 주의: LEFT JOIN + COALESCE(order_count, 0) 필수. INNER JOIN이면 해당 월 주문이 0건인 유저가
-- 조인에서 탈락해 검증 대상에서 빠진다 (01_설계보완_검토안.txt 0절 #2 참고).

SELECT * FROM (
    SELECT u.id AS user_id,
           u.membership_tier AS current_tier,
           COALESCE(computed.order_count, 0) AS order_count,
           CASE
             WHEN COALESCE(computed.order_count, 0) >= 31 THEN 'SERGEANT'
             WHEN COALESCE(computed.order_count, 0) >= 11 THEN 'CORPORAL'
             WHEN COALESCE(computed.order_count, 0) >= 3  THEN 'PFC'
             ELSE 'PRIVATE'
           END AS expected_tier
    FROM users u
    LEFT JOIN (
        SELECT user_id, COUNT(*) AS order_count
        FROM orders
        WHERE status = 'COMPLETED' AND paid_amount >= 10000
          AND completed_at >= :월시작 AND completed_at < :월종료
        GROUP BY user_id
    ) computed ON computed.user_id = u.id
) t
WHERE t.current_tier <> t.expected_tier
ORDER BY t.user_id;
