package com.mealiverit.api.order.controller;

import com.mealiverit.api.common.response.ApiResponse;
import com.mealiverit.api.order.dto.OrderCancelRequest;
import com.mealiverit.api.order.dto.OrderCreateRequest;
import com.mealiverit.api.order.dto.OrderResponse;
import com.mealiverit.api.order.service.OrderService;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/api/orders")
    public ApiResponse<OrderResponse> create(@RequestHeader("X-User-Id") Long userId,
                                             @RequestHeader("Idempotency-Key") String requestId,
                                             @RequestBody OrderCreateRequest request) {
        return ApiResponse.success(orderService.createOrder(userId, request, requestId));
    }

    @PatchMapping("/api/orders/{orderId}/cancel")
    public ApiResponse<OrderResponse> cancel(@PathVariable Long orderId,
                                             @RequestHeader("Idempotency-Key") String requestId,
                                             @RequestBody(required = false) OrderCancelRequest request) {
        Long couponIssueId = request != null ? request.couponIssueId() : null;
        return ApiResponse.success(orderService.cancelOrder(orderId, couponIssueId, requestId));
    }
}
