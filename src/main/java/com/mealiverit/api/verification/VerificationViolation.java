package com.mealiverit.api.verification;

import java.time.LocalDateTime;

public record VerificationViolation(
        String checkType,
        String referenceId,
        String detail,
        LocalDateTime detectedAt
) {
    public VerificationViolation(String checkType, String referenceId, String detail) {
        this(checkType, referenceId, detail, LocalDateTime.now());
    }
}