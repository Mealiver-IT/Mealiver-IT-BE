package com.mealiverit.api.order.controller;

import com.mealiverit.api.common.response.ApiResponse;
import com.mealiverit.api.order.dto.OrderCreateRequest;
import com.mealiverit.api.order.dto.OrderResponse;
import com.mealiverit.api.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderResponse> create(@RequestBody OrderCreateRequest request) {
        return ApiResponse.success(orderService.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderResponse> findById(@PathVariable Long id) {
        return ApiResponse.success(orderService.findById(id));
    }
}
