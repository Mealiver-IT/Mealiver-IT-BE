package com.mealiverit.entity.coupon;

import com.mealiverit.entity.user.MembershipTier;
import java.math.BigDecimal;

// 04_아키텍처.txt 6.1절: 계급별 차등 할인율(확정) — 캠페인마다 달라지는 값이 아닌 프로젝트 전역 고정 정책.
// RATE 타입 쿠폰에만 적용한다. FIXED 타입은 coupon.discountValue를 그대로 쓴다(호출측 CouponIssueService 책임).
public final class TierDiscountPolicy {

    private TierDiscountPolicy() {
    }

    public static BigDecimal rateFor(MembershipTier tier) {
        return switch (tier) {
            case PRIVATE, PFC -> new BigDecimal("0.10");
            case CORPORAL -> new BigDecimal("0.30");
            case SERGEANT -> new BigDecimal("0.50");
        };
    }
}
