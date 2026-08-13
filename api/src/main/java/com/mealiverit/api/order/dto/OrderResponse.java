package com.mealiverit.api.order.dto;

import com.mealiverit.entity.order.Order;
import com.mealiverit.entity.order.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResponse(
        Long id,
        BigDecimal orderAmount,
        BigDecimal paidAmount,
        OrderStatus status,
        LocalDateTime orderedAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderAmount(),
                order.getPaidAmount(),
                order.getStatus(),
                order.getOrderedAt()
        );
    }
}
