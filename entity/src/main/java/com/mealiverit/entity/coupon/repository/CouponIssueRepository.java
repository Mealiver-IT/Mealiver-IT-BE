package com.mealiverit.entity.coupon.repository;

import java.time.LocalDateTime;
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

    // 내 쿠폰함 전체 조회용 - 사용가능/사용함/회수됨/만료됨 모두 포함하되, 종료 상태(USED/CANCELED/EXPIRED)는
    // 각 시점(usedAt/canceledAt/expiredAt)으로부터 3일 지나면 노출 대상에서 제외
    @Query("SELECT ci FROM CouponIssue ci WHERE ci.userId = :userId AND (" +
            "ci.status = com.mealiverit.entity.coupon.CouponStatus.ISSUED " +
            "OR (ci.status = com.mealiverit.entity.coupon.CouponStatus.USED AND ci.usedAt >= :cutoff) " +
            "OR (ci.status = com.mealiverit.entity.coupon.CouponStatus.CANCELED AND ci.canceledAt >= :cutoff) " +
            "OR (ci.status = com.mealiverit.entity.coupon.CouponStatus.EXPIRED AND ci.expiredAt >= :cutoff)" +
            ")")
    List<CouponIssue> findVisibleCouponsForUser(@Param("userId") Long userId, @Param("cutoff") LocalDateTime cutoff);

    // 계급별 혜택 조회(GET /api/members/me/benefits)용
    // CouponIssue가 Campaign과 JPA 연관관계가 없어(CampaignId만 갖고 있음) 서브쿼리로 campaign_type을 필터링함
    @Query("SELECT ci FROM CouponIssue ci WHERE ci.userId = :userId AND ci.status = :status " +
            "AND ci.campaignId IN (SELECT c.id FROM Campaign c WHERE c.campaignType = :campaignType)")
    List<CouponIssue> findByUserIdAndStatusAndCampaignType(@Param("userId") Long userId,
                                                           @Param("status") CouponStatus status,
                                                           @Param("campaignType") CampaignType campaignType);
}
