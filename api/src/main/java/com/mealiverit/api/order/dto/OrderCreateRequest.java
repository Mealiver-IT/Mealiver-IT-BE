package com.mealiverit.api.order.dto;

public record OrderCreateRequest(String productName, int quantity) {
}
