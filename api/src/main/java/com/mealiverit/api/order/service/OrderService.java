package com.mealiverit.api.order.service;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.coupon.service.CouponIssueService;
import com.mealiverit.api.order.dto.OrderCreateRequest;
import com.mealiverit.api.order.dto.OrderResponse;
import com.mealiverit.entity.order.Order;
import com.mealiverit.entity.order.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
        // couponIssueId를 Order 생성 시점에 같이 저장 - 취소할 때 다시 안받아도 되도록
        Order order = new Order(userId, request.orderAmount(), request.paidAmount(), LocalDateTime.now(), request.couponIssueId());
        orderRepository.save(order);

        if (request.couponIssueId() != null) {
            couponIssueService.markUsed(request.couponIssueId(), requestId);
        }
        return OrderResponse.from(order);
    }

    // 주문취소 - 쿠폰이 적용된 주문이었으면 안에서 재사용 가능하게 복귀 (markReturnedToIssued)
    // 관리자 강제회수(markCanceled)와는 별개 경로 - 여기선 절대 markCanceled() 안씀.
    // couponIssueId 파라미터 제거 - 클라이언트가 안 보내도 order 자신이 들고 있는 값을 씀
    public OrderResponse cancelOrder(Long orderId, String requestId) {
        Order order = findOrderOrThrow(orderId);
        order.cancel();
        orderRepository.save(order);

        if (order.getCouponIssueId() != null) {
            couponIssueService.markReturnedToIssued(order.getCouponIssueId(), requestId);
        }
        return OrderResponse.from(order);
    }

    // 주문 상세 조회 - 주문번호만 알면 남의 주문을 볼 수 없도록, 요청자(X-User-Id)와 주문 소유자가 같은지 검증
    // 다르면 ORDER_ACCESS_DENIED(403)
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId, Long userId) {
        Order order = findOrderOrThrow(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }
        return OrderResponse.from(order);
    }

    // 주문 목록 조회 - 헤더의 userId로 본인의 주문마 조회
    // 쿼리 자체가 userId로 필터링되므로 getOrder 처럼 별도 소유권 체크는 필요 없음
    @Transactional(readOnly = true)
    public List<OrderResponse> getOrders(Long userId) {
        return orderRepository.findByUserIdOrderByOrderedAtDesc(userId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    private Order findOrderOrThrow(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }
}
