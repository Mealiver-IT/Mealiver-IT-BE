package com.mealiverit.entity.coupon;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(CouponStatus from, CouponStatus to) {
        super("Cannot transition coupon status from " + from + " to " + to);
    }
}
