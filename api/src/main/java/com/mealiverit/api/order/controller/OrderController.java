package com.mealiverit.api.order.controller;

import com.mealiverit.api.common.response.ApiResponse;
import com.mealiverit.api.order.dto.OrderCancelRequest;
import com.mealiverit.api.order.dto.OrderCreateRequest;
import com.mealiverit.api.order.dto.OrderResponse;
import com.mealiverit.api.order.service.OrderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // 주문, 결제 생성 - 장바구니 기반 주문 생성
    // 쿠폰이 적용된 경우 OrderService 내부에서 같은 트랜잭션으로 쿠폰도 USED 처리됨
    @PostMapping("/api/orders")
    public ApiResponse<OrderResponse> create(@RequestHeader("X-User-Id") Long userId,
                                             @RequestHeader("Idempotency-Key") String requestId,
                                             @RequestBody OrderCreateRequest request) {
        return ApiResponse.success(orderService.createOrder(userId, request, requestId));
    }

    // 주문 상세 조회 - 요청자와 주문 소유자가 다르면 OrderService.getOrder()가 403을 던짐
    @GetMapping("/api/orders/{orderId}")
    public ApiResponse<OrderResponse> getOrder(@PathVariable Long orderId,
                                               @RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.success(orderService.getOrder(orderId, userId));
    }

    // 주문 목록 조회 - 헤더의 userId 본인 주문만 반환. 페이지네이션 없음(내 쿠폰함 조회와 동일 스타일)
    @GetMapping("/api/orders")
    public ApiResponse<List<OrderResponse>> getOrders(@RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.success(orderService.getOrders(userId));
    }

    // 주문 취소 - 쿠폰이 적용된 주문이었으면 OrderService 내부에서 쿠폰을 재사용 가능하도록 복귀시킴
    // 관리자 강제회수 markCanceled와는 별개 경로
    @PatchMapping("/api/orders/{orderId}/cancel")
    public ApiResponse<OrderResponse> cancel(@PathVariable Long orderId,
                                             @RequestHeader("Idempotency-Key") String requestId,
                                             @RequestBody(required = false) OrderCancelRequest request) {
        Long couponIssueId = request != null ? request.couponIssueId() : null;
        return ApiResponse.success(orderService.cancelOrder(orderId, couponIssueId, requestId));
    }
}
