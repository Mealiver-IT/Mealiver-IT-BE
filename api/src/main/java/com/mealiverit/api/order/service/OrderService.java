package com.mealiverit.api.order.service;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.coupon.service.CouponIssueService;
import com.mealiverit.api.order.dto.OrderCreateRequest;
import com.mealiverit.api.order.dto.OrderResponse;
import com.mealiverit.entity.order.Order;
import com.mealiverit.entity.order.OrderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CouponIssueService couponIssueService;

    public OrderService(OrderRepository orderRepository, CouponIssueService couponIssueService) {
        this.orderRepository = orderRepository;
        this.couponIssueService = couponIssueService;
    }

    // 결제완료(POST /api/orders) 시점 - 쿠폰 적용된 주문이면 쿠폰도 USED로 전이
    // 주문 저장과 쿠폰 상태전이는 각자 독립된 트랜잭션으로 커밋된다.
    // markUsed 자체가 멱등키 + 재시도로 안전하므로 원자성을 잃어도 정합성엔 문제 없음.
    public OrderResponse createOrder(Long userId, OrderCreateRequest request, String requestId) {
        Order order = new Order(userId, request.orderAmount(), request.paidAmount(), LocalDateTime.now());
        orderRepository.save(order);

        if (request.couponIssueId() != null) {
            couponIssueService.markUsed(request.couponIssueId(), requestId);
        }
        return OrderResponse.from(order);
    }

    // 주문취소 - 쿠폰이 적용된 주문이었으면 안에서 재사용 가능하게 복귀 (markReturnedToIssued)
    // 관리자 강제회수(markCanceled)와는 별개 경로 - 여기선 절대 markCanceled() 안씀.
    public OrderResponse cancelOrder(Long orderId, Long couponIssueId, String requestId) {
        Order order = findOrderOrThrow(orderId);
        order.cancel();
        orderRepository.save(order);

        if (couponIssueId != null) {
            couponIssueService.markReturnedToIssued(couponIssueId, requestId);
        }
        return OrderResponse.from(order);
    }

    private Order findOrderOrThrow(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }
}
