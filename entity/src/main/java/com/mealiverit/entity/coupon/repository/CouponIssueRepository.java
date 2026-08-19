package com.mealiverit.entity.coupon.repository;

import java.util.List;
import java.util.Optional;

import com.mealiverit.entity.campaign.CampaignType;
import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

    // idempotency 체크 (04_아키텍처.txt 5.1절 1단계)
    Optional<CouponIssue> findByIdempotencyKey(String idempotencyKey);

    // unique 제약(uk_campaign_user) 위반 시 기존 레코드 반환용 (04_아키텍처.txt 5.1절)
    Optional<CouponIssue> findByCampaignIdAndUserId(Long campaignId, Long userId);

    // 검증 쿼리 (a)(c), 08_개발표준.txt 5.1절 테스트에서 사용
    long countByCampaignId(Long campaignId);

    // GET /api/users/{userId}/coupons 목록 조회용
    List<CouponIssue> findByUserIdAndStatus(Long userId, CouponStatus status);

    // 계급별 혜택 조회(GET /api/members/me/benefits)용
    // CouponIssue가 Campaign과 JPA 연관관계가 없어(CampaignId만 갖고 있음) 서브쿼리로 campaign_type을 필터링함
    @Query("SELECT ci FROM CouponIssue ci WHERE ci.userId = :userId AND ci.status = :status " +
            "AND ci.campaignId IN (SELECT c.id FROM Campaign c WHERE c.campaignType = :campaignType)")
    List<CouponIssue> findByUserIdAndStatusAndCampaignType(@Param("userId") Long userId,
                                                           @Param("status") CouponStatus status,
                                                           @Param("campaignType") CampaignType campaignType);
}
