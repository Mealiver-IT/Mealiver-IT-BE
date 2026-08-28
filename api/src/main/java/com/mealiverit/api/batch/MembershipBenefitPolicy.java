package com.mealiverit.api.batch;

import com.mealiverit.api.coupon.DiscountType;
import com.mealiverit.api.user.MembershipTier;

import java.math.BigDecimal;
import java.util.List;

// FR-MBR-004(02_기능명세서_개정본.txt) 계급별 월간 혜택 쿠폰 정책
// 이등병 웰컴쿠폰(가입 1회성)은 이번 범위 제외 - 실제 회원가입 API가 없어 "가입 시점" 트리거 자체가 정의되지 않음.
// "배달비 무료"는 Order/Coupon에 배달비 필드가 없어 정액 3,000원 할인으로 근사(팀 확인 필요, 숫자만 바꾸면 됨).
public final class MembershipBenefitPolicy {

    private MembershipBenefitPolicy() {}

    public record BenefitCoupon(DiscountType discountType, BigDecimal discountValue) {}

    public static List<BenefitCoupon> couponsFor(MembershipTier tier) {
        return switch (tier) {
            case PRIVATE -> List.of();
            case PFC -> List.of(
                    new BenefitCoupon(DiscountType.FIXED, new BigDecimal("1000")),
                    new BenefitCoupon(DiscountType.FIXED, new BigDecimal("1000"))
            );
            case CORPORAL -> List.of(
                    new BenefitCoupon(DiscountType.FIXED, new BigDecimal("3000")), //배달비 무료 근사
                    new BenefitCoupon(DiscountType.FIXED, new BigDecimal("3000")),
                    new BenefitCoupon(DiscountType.RATE, new BigDecimal("0.10"))
            );
            case SERGEANT ->  List.of(
                    new BenefitCoupon(DiscountType.RATE, new BigDecimal("0.20")),
                    new BenefitCoupon(DiscountType.RATE, new BigDecimal("0.20"))
            );
        };
    }
}
