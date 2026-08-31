package com.mealiverit.api.verification.report;

// 대시보드용 - 실제로 Slack/노션에 알림이 나가는 검증 배치가 2개(DailyConsistencyVerificationJob:
// 일간, 6개 체크 / TierOrdersMismatchJob: 월간, 계급-주문 정합성 1개 체크)라서 각각의 최근 실행을
// 따로 보여준다. 예전엔 daily만 조회해서 tierMonthly 쪽 이상값은 대시보드에서 아예 안 보였음(2026-08-26 확인).
public record VerificationOverviewResponse(
        VerificationSummaryResponse daily,
        VerificationSummaryResponse tierMonthly
) {
}
