package com.mealiverit.api.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    COUPON_INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "허용되지 않은 쿠폰 상태 전이입니다."),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 쿠폰 발급 건입니다."),

    // 발급(CouponIssuanceService.issue()) 관련
    SOLD_OUT(HttpStatus.CONFLICT, "재고가 모두 소진되었습니다."),
    MEMBERSHIP_TIER_NOT_ELIGIBLE(HttpStatus.FORBIDDEN, "해당 등급은 이 쿠폰을 받을 수 없습니다."),
    ALREADY_ISSUED(HttpStatus.CONFLICT, "이미 발급받은 쿠폰입니다."),
    // FR-FCFS-031(02_기능명세서_개정본.txt): 성공/조기마감/중복수령/등급미달/캠페인미오픈을 구분해서 안내해야 함
    CAMPAIGN_NOT_OPEN(HttpStatus.CONFLICT, "캠페인이 아직 열리지 않았거나 이미 마감되었습니다."),
    // 2026-08-19 부하테스트(coupon_mixed_5k_x4.js) 실측: 같은 유저의 거의 동시 중복요청이 idempotency
    // 체크(락 없는 SELECT)를 전부 통과해 캠페인 락을 반복 획득/롤백-재획득하며 hot row 경합을 증폭시킴.
    // CouponIssuanceDuplicateGuard(Redis SETNX 기반 사전 필터)가 이 경우 DB까지 가지 않고 즉시 거부.
    DUPLICATE_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "동일 요청이 처리 중입니다. 잠시 후 다시 시도해주세요."),

    // 캠페인 관리자 CRUD/상태전이 관련
    CAMPAIGN_NOT_FOUND(HttpStatus.NOT_FOUND, "캠페인을 찾을 수 없습니다."),
    CAMPAIGN_INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "허용되지 않은 캠페인 상태 전이입니다."),
    CAMPAIGN_STATE_CONFLICT(HttpStatus.CONFLICT, "다른 요청이 먼저 캠페인 상태를 변경했습니다. 다시 시도해주세요."),

    // 주문 관련
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 주문입니다."),
    ORDER_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인의 주문만 조회할 수 있습니다."),

    // 존재하지 않는 X-User-Id로 조회 시 사용
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."),


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
