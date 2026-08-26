package com.mealiverit.api.coupon.dto;

import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.user.MembershipTier;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 관리자 쿠폰 강제 회수 화면 전용 - CouponIssueResponse(회원용, campaignName 포함)와 달리
// 여기선 캠페인이 이미 경로 파라미터로 고정돼있고 대신 회수 대상 식별에 필요한 userId가 필요하다.
// discountType/discountValue/issuedMembershipTier는 전부 발급 시점 스냅샷(CouponIssue.java 주석
// 참고) - 지금 유저 계급이 바뀌었어도 "그때 몇 퍼로 받았는지"를 그대로 보여준다.
public record CouponIssueAdminResponse(
        Long id,
        Long userId,
        String couponCode,
        CouponStatus status,
        MembershipTier issuedMembershipTier,
        DiscountType discountType,
        BigDecimal discountValue,
        LocalDateTime issuedAt,
        LocalDateTime validUntil,
        LocalDateTime usedAt,
        LocalDateTime canceledAt
) {

    public static CouponIssueAdminResponse from(CouponIssue issue) {
        return new CouponIssueAdminResponse(
                issue.getId(),
                issue.getUserId(),
                issue.getCouponCode(),
                issue.getStatus(),
                issue.getIssuedMembershipTier(),
                issue.getDiscountType(),
                issue.getDiscountValue(),
                issue.getIssuedAt(),
                issue.getValidUntil(),
                issue.getUsedAt(),
                issue.getCanceledAt()
        );
    }
}
