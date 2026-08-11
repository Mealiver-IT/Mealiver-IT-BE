package com.mealiverit.entity.coupon.repository;

import java.util.List;
import java.util.Optional;

import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    // idempotency 체크 (04_아키텍처.txt 5.1절 1단계)
    Optional<CouponIssue> findByIdempotencyKey(String idempotencyKey);

    // unique 제약(uk_campaign_user) 위반 시 기존 레코드 반환용 (04_아키텍처.txt 5.1절)
    Optional<CouponIssue> findByCampaignIdAndUserId(Long campaignId, Long userId);

    // 검증 쿼리 (a)(c), 08_개발표준.txt 5.1절 테스트에서 사용
    long countByCampaignId(Long campaignId);

    //GET /api/users/{userId}/coupons 목록 조회용
    List<CouponIssue> findByUserIdAndStatus(Long userId, CouponStatus status);
}
