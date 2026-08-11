package com.mealiverit.api.coupon.service;

import com.mealiverit.entity.coupon.entity.CouponIssue;

// 04_아키텍처.txt 5.1절 — 신규 발급과 "이미 처리된 요청(멱등 재응답)"을 컨트롤러가 구분할 수 있게 분리.
// 둘 다 CouponIssue는 있지만, ALREADY_PROCESSED는 HTTP 상태코드를 다르게 줄 수도 있어 상태를 남긴다.
public record IssueResult(Status status, CouponIssue couponIssue) {

    public enum Status {
        SUCCESS,
        ALREADY_PROCESSED
    }

    public static IssueResult success(CouponIssue couponIssue) {
        return new IssueResult(Status.SUCCESS, couponIssue);
    }

    public static IssueResult alreadyProcessed(CouponIssue couponIssue) {
        return new IssueResult(Status.ALREADY_PROCESSED, couponIssue);
    }
}
