package com.mealiverit.api.campaign.dto;

import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.coupon.entity.Coupon;
import java.math.BigDecimal;

public record CouponResponse(
        Long id,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscountAmount,
        int validHours
) {
    public static CouponResponse of(Coupon coupon) {
        return new CouponResponse(coupon.getId(), coupon.getDiscountType(), coupon.getDiscountValue(),
                coupon.getMinOrderAmount(), coupon.getMaxDiscountAmount(), coupon.getValidHours());
    }
}
