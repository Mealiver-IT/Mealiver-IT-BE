package com.mealiverit.api.coupon;

import java.util.Map;
import java.util.Set;

// 04_아키텍처.txt 3절 상태 머신 그대로.
public enum CouponStatus {
    ISSUED, USED, CANCELED, EXPIRED;

    private static final Map<CouponStatus, Set<CouponStatus>> TRANSITIONS = Map.of(
            ISSUED, Set.of(USED, CANCELED, EXPIRED),
            USED, Set.of(ISSUED),   // 주문 취소 시 재사용 복귀만 허용, 이미 사용된 쿠폰은 관리자 강제 회수 불가
            CANCELED, Set.of(),
            EXPIRED, Set.of()
    );

    public boolean canTransitionTo(CouponStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
