package com.mealiverit.api.order.dto;

import java.math.BigDecimal;

public record OrderCreateRequest(
        BigDecimal orderAmount,
        BigDecimal paidAmount,
        Long couponIssueId // nullable - 쿠폰 미적용 주문이면 생략
) {
}
