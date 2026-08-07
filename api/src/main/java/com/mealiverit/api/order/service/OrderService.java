package com.mealiverit.api.order.service;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.order.dto.OrderCreateRequest;
import com.mealiverit.api.order.dto.OrderResponse;
import com.mealiverit.entity.order.Order;
import com.mealiverit.entity.order.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse create(OrderCreateRequest request) {
        Order order = new Order(request.productName(), request.quantity());
        Order saved = orderRepository.save(order);
        return OrderResponse.from(saved);
    }

    public OrderResponse findById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        return OrderResponse.from(order);
    }
}
