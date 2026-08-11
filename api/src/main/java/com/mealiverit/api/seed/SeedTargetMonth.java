package com.mealiverit.api.seed;

import java.time.YearMonth;

// OrderSeedRunner가 심는 orders.completed_at과 MembershipTierSeedRunner가 집계하는 윈도우가
// 반드시 같은 달이어야 목표 계급분포(40/30/20/10)가 맞는다. 하나의 프로퍼티로 두 러너의
// 기준월을 통일한다 — 둘 다 이 클래스를 거치면 어긋날 일이 없다.
final class SeedTargetMonth {

    private SeedTargetMonth() {
    }

    static YearMonth resolve() {
        String override = System.getProperty("seed.orders.target-month");
        if (override != null && !override.isBlank()) {
            return YearMonth.parse(override.trim());
        }
        return YearMonth.now().minusMonths(1);
    }
}
