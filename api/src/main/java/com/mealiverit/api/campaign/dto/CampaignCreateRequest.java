package com.mealiverit.api.campaign.dto;

import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.user.MembershipTier;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

// 캠페인:쿠폰 1:1(04_아키텍처.txt 1절) — 관리자가 한 번에 캠페인과 쿠폰 정책을 함께 생성한다.
// minMembershipTier가 null이면 전 회원 대상 캠페인.
public record CampaignCreateRequest(
        @NotBlank String name,
        @Positive int totalStock,
        MembershipTier minMembershipTier,
        @NotNull DiscountType discountType,
        @NotNull BigDecimal discountValue,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscountAmount,
        @Min(1) int validHours
) {
}
