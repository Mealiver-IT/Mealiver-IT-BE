package com.mealiverit.api.coupon.dto;

import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.coupon.entity.CouponIssue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CouponIssueResponse(
        Long id,
        Long campaignId,
        String couponCode,
        String campaignName,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal maxDiscountAmount,
        CouponStatus status,
        LocalDateTime issuedAt,
        LocalDateTime validUntil
) {

    public static CouponIssueResponse from(CouponIssue issue, String campaignName) {
        return new CouponIssueResponse(
                issue.getId(),
                issue.getCampaignId(),
                issue.getCouponCode(),
                campaignName,
                issue.getDiscountType(),
                issue.getDiscountValue(),
                issue.getMaxDiscountAmount(),
                issue.getStatus(),
                issue.getIssuedAt(),
                issue.getValidUntil()
        );
    }
}
