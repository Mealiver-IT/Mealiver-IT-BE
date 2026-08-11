package com.mealiverit.api.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    COUPON_INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "허용되지 않은 쿠폰 상태 전이입니다."),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 쿠폰 발급 건입니다."),

    // 발급(CouponIssueService.issue()) 관련
    SOLD_OUT(HttpStatus.CONFLICT, "재고가 모두 소진되었습니다."),
    MEMBERSHIP_TIER_NOT_ELIGIBLE(HttpStatus.FORBIDDEN, "해당 등급은 이 쿠폰을 받을 수 없습니다."),
    ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 발급받은 쿠폰입니다."),









    ;

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
