package com.mealiverit.api.verification.report;

import java.time.LocalDateTime;

public record AnomalyDetail(

        CheckType checkType,

        String referenceId,

        String detail,

        LocalDateTime detectedAt

) {
}