package com.mealiverit.api.verification.report;

import com.mealiverit.api.verification.report.CheckType;
import org.springframework.batch.core.BatchStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ConsistencyReport(
    long jobExecutionId,
    String jobName,
    LocalDateTime startTime,
    BatchStatus status,
    Map<CheckType, Long> anomalyCounts,
    List<String> failedSteps
) {
    public boolean hasAnomalies() {
        return anomalyCounts.values().stream().anyMatch(c -> c > 0);
    }
}