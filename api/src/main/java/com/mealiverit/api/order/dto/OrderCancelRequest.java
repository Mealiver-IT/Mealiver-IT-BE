package com.mealiverit.api.order.dto;

public record OrderCancelRequest(
        Long couponIssueId //nullable - 쿠폰 미적용 주문이었으면 생략
) {
}
