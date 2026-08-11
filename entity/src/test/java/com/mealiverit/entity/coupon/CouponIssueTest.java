package com.mealiverit.entity.coupon;

import static org.assertj.core.api.Assertions.*;

import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.user.MembershipTier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class CouponIssueTest {

    //테스트마다 새 ISSUED 상태 쿠폰을 만들기 위한 헬퍼
    private CouponIssue newIssueCoupon() {
        Coupon coupon = new Coupon(1L, DiscountType.FIXED, BigDecimal.valueOf(1000), null, null, 24);
        return CouponIssue.issue(1L, 1L, "idem-" + UUID.randomUUID(), coupon, MembershipTier.PRIVATE, BigDecimal.valueOf(1000));
    }

    //ISSUED 쿠폰에 markUsed() 호출 시 USED로 바뀌고 usedAt이 기록되는지 확인
    @Test
    void issued_markUsed_transitionsToUsed() {
        CouponIssue issue = newIssueCoupon();

        issue.markUsed();

        assertThat(issue.getStatus()).isEqualTo(CouponStatus.USED);
        assertThat(issue.getUsedAt()).isNotNull();
    }

    //ISSUED 쿠폰에 markCanceled() 호출 시 CANCELED로 바뀌고 canceledAt이 기록되는지 확인
    @Test
    void issued_markCanceled_transitionsToCanceled() {
        CouponIssue issue = newIssueCoupon();

        issue.markCanceled();

        assertThat(issue.getStatus()).isEqualTo(CouponStatus.CANCELED);
        assertThat(issue.getCanceledAt()).isNotNull();
    }

    //ISSUED 쿠폰에 markExpired() 호출 시 EXPIRED로 바뀌고 전달한 시각이 그대로 expiredAt에 저장되는지 확인 (만료 배치가 쓰는 경로)
    @Test
    void issued_markExpired_transitionsToExpired() {
        CouponIssue issue = newIssueCoupon();
        LocalDateTime expiredAt = LocalDateTime.now();

        issue.markExpired(expiredAt);

        assertThat(issue.getStatus()).isEqualTo(CouponStatus.EXPIRED);
        assertThat(issue.getExpiredAt()).isEqualTo(expiredAt);
    }

    //사용된 (USED) 쿠폰도 환불 시나리오로 CANCELED 전이가 되는지 확인
    @Test
    void used_markCanceled_transitionsToCanceled_refund() {
        CouponIssue issue = newIssueCoupon();
        issue.markUsed();

        issue.markCanceled();

        assertThat(issue.getStatus()).isEqualTo(CouponStatus.CANCELED);
    }

    //이미 USED인 쿠폰에 markUsed()를 또 호출하면 예외가 터지는지 확인 (중복 사용 방지)
    @Test
    void used_markUsed_throwsInvalidStateTransition() {
        CouponIssue issue = newIssueCoupon();
        issue.markUsed();

        assertThatThrownBy(issue::markUsed).isInstanceOf(InvalidStateTransitionException.class);
    }

    //이미 CANCELED인 쿠폰에 markUsed()를 호출하면 예외가 터지는지 확인 (종단 상태에서의 역행 방지)
    @Test
    void canceled_markUsed_throwsInvalidStateTransition() {
        CouponIssue issue = newIssueCoupon();
        issue.markCanceled();

        assertThatThrownBy(issue::markUsed).isInstanceOf(InvalidStateTransitionException.class);
    }

    //이미 CANCELED인 쿠폰에 markCalnceled()를 또 호출하면 예외가 터지는지 확인 (중복 취소 방지)
    @Test
    void canceled_markCanceled_throwsInvalidStateTransition() {
        CouponIssue issue = newIssueCoupon();
        issue.markCanceled();

        assertThatThrownBy(issue::markCanceled).isInstanceOf(InvalidStateTransitionException.class);
    }

    //이미 EXPIRED인 쿠폰에 markUsed()를 호출하면 예외가 터지는지 확인 (만료된 쿠폰 사용 방지)
    @Test
    void expired_markUsed_throwsInvalidStateTransition() {
        CouponIssue issue = newIssueCoupon();
        issue.markExpired(LocalDateTime.now());

        assertThatThrownBy(issue::markUsed).isInstanceOf(InvalidStateTransitionException.class);
    }
}
