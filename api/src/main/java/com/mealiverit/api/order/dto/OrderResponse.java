package com.mealiverit.api.order.dto;

import com.mealiverit.entity.order.Order;
import com.mealiverit.entity.order.OrderStatus;
import java.time.LocalDateTime;

public record OrderResponse(
        Long id,
        String productName,
        int quantity,
        OrderStatus status,
        LocalDateTime createdAt
) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getProductName(),
                order.getQuantity(),
                order.getStatus(),
                order.getCreatedAt()
        );
    }
}
