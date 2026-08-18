package com.mealiverit.api.membership.dto;

import com.mealiverit.api.batch.MembershipTierBatchJob;

import java.time.YearMonth;

// 계급 갱신 수동 실행(POST /api/admin/membership/refresh) 응답 - 배치 1회 실행 결과 요약
public record MembershipRefreshResponse(
        YearMonth targetMonth,
        int totalUsers,
        long changedCount
) {
    public static MembershipRefreshResponse from(MembershipTierBatchJob.Result result) {
        return new MembershipRefreshResponse(
                result.getTargetMonth(),
                result.getTotalUsers(),
                result.getChangedCount()
        );
    }
}
