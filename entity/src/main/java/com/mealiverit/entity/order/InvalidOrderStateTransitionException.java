package com.mealiverit.entity.order;

// InvalidCampaignStateTransitionException과 동일한 패턴
// entity 모듈은 api를 몰라서 도메인 예외를 직접 던지고, GlobalExceptionHandler가 HTTP 응답으로 번역한다
public class InvalidOrderStateTransitionException extends RuntimeException {

    public InvalidOrderStateTransitionException(OrderStatus from, OrderStatus to) {
        super("Cannot transition order status from " + from + " to " + to);
    }
}
