package com.mealiverit.api.verification.report;

import java.util.Arrays;

public enum CheckType {
    STOCK_OVERISSUE("재고 초과"),
    COUNTER_MISMATCH("카운터-이력 일치"),
    STATE_MISSING_LOG("로그 없는 레코드"),
    STATE_INVALID_TRANSITION("허용 안 된 전이"),
    STATE_BROKEN_CHAIN("로그 체인 연속성"),
    TIER_ELIGIBILITY_VIOLATION("등급 미달 발급"),
    TIER_CONSISTENCY_MISMATCH("계급-주문 정합성");

    private final String label;

    CheckType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static CheckType fromCode(String code) {
        return Arrays.stream(values())
            .filter(c -> c.name().equals(code))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown check_type: " + code));
    }
}