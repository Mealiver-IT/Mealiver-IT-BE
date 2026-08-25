package com.mealiverit.entity.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CouponStatusTest {
    //ISSUED는 USED/CANCELED/EXPIRED로 전이가 가능해야 한다
    @Test
    void issued_canTransitionToUsedCanceledExpired() {
        assertThat(CouponStatus.ISSUED.canTransitionTo(CouponStatus.USED)).isTrue();
        assertThat(CouponStatus.ISSUED.canTransitionTo(CouponStatus.CANCELED)).isTrue();
        assertThat(CouponStatus.ISSUED.canTransitionTo(CouponStatus.EXPIRED)).isTrue();
    }

    // USED는 ISSUED(주문취소 시 본인 재사용 복귀)로만 전이 가능하고, 그 외(자기자신 포함 역행)는 불가능해야 한다.
    // USED->EXPIRED는 의도적으로 불허 — ISSUED로 복귀한 뒤 기한이 지났으면 만료 배치가 알아서 처리하므로 직접 전이는 불필요
    @Test
    void used_canTransitionToIssuedOnly() {
        assertThat(CouponStatus.USED.canTransitionTo(CouponStatus.CANCELED)).isFalse();
        assertThat(CouponStatus.USED.canTransitionTo(CouponStatus.ISSUED)).isTrue();
        assertThat(CouponStatus.USED.canTransitionTo(CouponStatus.USED)).isFalse();
        assertThat(CouponStatus.USED.canTransitionTo(CouponStatus.EXPIRED)).isFalse();
    }

    //CANCELED는 종단 상태 - 어떤 상태로도 전이 불가해야 한다.
    @Test
    void canceled_isTerminal() {
        for (CouponStatus target : CouponStatus.values()) {
            assertThat(CouponStatus.CANCELED.canTransitionTo(target)).isFalse();
        }
    }

    //EXPIRED는 종단 상태 - 어떤 상태로도 전이 불가해야 한다.
    @Test
    void expired_isTerminal() {
        for (CouponStatus target : CouponStatus.values()) {
            assertThat(CouponStatus.EXPIRED.canTransitionTo(target)).isFalse();
        }
    }
}
