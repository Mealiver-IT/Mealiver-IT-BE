package com.mealiverit.api.verification.report;

import org.springframework.batch.core.BatchStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ConsistencyReport(

        long jobExecutionId,

        String jobName,

        LocalDateTime startTime,

        LocalDateTime endTime,

        BatchStatus status,

        Map<CheckType, Long> anomalyCounts,

        List<StepExecutionSummary> stepExecutions,

        List<String> failedSteps,

        List<AnomalyDetail> anomalyDetails

) {

    public boolean hasAnomalies() {

        return anomalyCounts.values()
                .stream()
                .anyMatch(count -> count > 0);
    }

    public long totalViolationCount() {

        return anomalyCounts.values()
                .stream()
                .mapToLong(Long::longValue)
                .sum();
    }
    
    public long totalVerificationCount() {

        return stepExecutions.stream()
                .mapToLong(
                        StepExecutionSummary::readCount
                )
                .sum();
    }

    public long durationMillis() {

        if (startTime == null || endTime == null) {
            return 0L;
        }

        return Duration.between(
                startTime,
                endTime
        ).toMillis();
    }
}