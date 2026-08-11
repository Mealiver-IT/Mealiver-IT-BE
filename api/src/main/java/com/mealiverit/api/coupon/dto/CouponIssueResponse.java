package com.mealiverit.api.coupon.dto;

import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.coupon.entity.CouponIssue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CouponIssueResponse(
        Long id,
        String couponCode,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal maxDiscountAmount,
        CouponStatus status,
        LocalDateTime issuedAt,
        LocalDateTime validUntil
) {

    public static CouponIssueResponse from(CouponIssue issue) {
        return new CouponIssueResponse(
                issue.getId(),
                issue.getCouponCode(),
                issue.getDiscountType(),
                issue.getDiscountValue(),
                issue.getMaxDiscountAmount(),
                issue.getStatus(),
                issue.getIssuedAt(),
                issue.getValidUntil()
        );
    }
}
