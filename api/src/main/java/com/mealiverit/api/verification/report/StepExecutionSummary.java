package com.mealiverit.api.verification.report;

import java.time.Duration;
import java.time.LocalDateTime;

public record StepExecutionSummary(

        String stepName,

        String status,

        LocalDateTime startTime,

        LocalDateTime endTime,

        long readCount,

        long writeCount,

        long filterCount,

        long readSkipCount,

        long processSkipCount,

        long writeSkipCount,
        
        long scannedCount

) {

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