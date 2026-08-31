package com.mealiverit.api.verification.report;

import java.time.LocalDateTime;
import java.util.Map;

// 관리자 대시보드용 - 가장 최근 DailyConsistencyVerificationJob 실행 1건의 요약.
// hasRun=false면 아직 이 Job이 한 번도 안 돌았다는 뜻(빈 결과와 구분하기 위해 별도 필드로 둠).
public record VerificationSummaryResponse(
        boolean hasRun,
        Long jobExecutionId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Long durationSeconds,
        String status,
        long totalAnomalies,
        Map<String, Long> anomalyCounts
) {
    public static VerificationSummaryResponse notRunYet() {
        return new VerificationSummaryResponse(false, null, null, null, null, null, 0, Map.of());
    }
}
