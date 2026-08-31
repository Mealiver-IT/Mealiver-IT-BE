-- (d) 회원 전용 쿠폰이 등급 미달 유저에게 발급된 적이 없는가
-- 설계 근거: docs/planning/05_시스템설계.txt 1.1절 (d), 09_기획서.txt 6.3절, 04_아키텍처.txt 6절
-- 결과: 0 rows 여야 함. FIELD()로 등급 순서를 정수 인덱스처럼 비교(PRIVATE=1 < ... < SERGEANT=4)
--
-- 주의: users.membership_tier(현재 계급)와 비교하면 안 됨 — 계급은 매월 재산정되는 변동값이라
-- 발급 후 강등된 유저가 false positive로 잡힌다. 반드시 발급 시점 스냅샷 컬럼과 비교한다.

-- ORDER BY ci.id;


SELECT ci.id, ci.user_id,
       ci.issued_membership_tier AS tier_at_issue,
       c.min_membership_tier AS required_tier,
       ci.campaign_id,
       ci.issued_at
FROM coupon_issue ci
JOIN campaign c ON c.id = ci.campaign_id
WHERE c.min_membership_tier IS NOT NULL
  AND FIELD(ci.issued_membership_tier, 'PRIVATE','PFC','CORPORAL','SERGEANT')
    < FIELD(c.min_membership_tier, 'PRIVATE','PFC','CORPORAL','SERGEANT');
