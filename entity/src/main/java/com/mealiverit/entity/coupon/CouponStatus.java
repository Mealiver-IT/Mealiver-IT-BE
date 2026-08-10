package com.mealiverit.entity.coupon;

import java.util.Map;
import java.util.Set;

// 04_아키텍처.txt 3절 상태 머신 그대로.
public enum CouponStatus {
    ISSUED, USED, CANCELED, EXPIRED;

    private static final Map<CouponStatus, Set<CouponStatus>> TRANSITIONS = Map.of(
            ISSUED, Set.of(USED, CANCELED, EXPIRED),
            USED, Set.of(CANCELED),   // 사용 후 취소(환불)만 허용, 그 외 역행 불가
            CANCELED, Set.of(),
            EXPIRED, Set.of()
    );

    public boolean canTransitionTo(CouponStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
