package com.mealiverit.api.batch;

import com.mealiverit.api.user.MembershipTier;

// 09_기획서.txt 6.2절 / 05_시스템설계.txt 1.1(f)의 계급 구간 임계값.
// MembershipTierBatchJob과 정합성 검증 쿼리(f)가 서로 다른 로직을 쓰면 둘은 영원히 일치할 수 없으므로,
// 이 클래스를 유일한 판정 기준으로 두고 검증 SQL 쪽 CASE WHEN은 이 값을 그대로 미러링해야 한다.
public final class MembershipTierCalculator {

    private MembershipTierCalculator() {
    }

    public static MembershipTier fromCompletedOrderCount(int completedOrderCount) {
        if (completedOrderCount >= 31) {
            return MembershipTier.SERGEANT;
        }
        if (completedOrderCount >= 11) {
            return MembershipTier.CORPORAL;
        }
        if (completedOrderCount >= 3) {
            return MembershipTier.PFC;
        }
        return MembershipTier.PRIVATE;
    }
}
