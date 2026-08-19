package com.mealiverit.api.verification;

public record VerificationViolation(String checkType, String referenceId, String detail) {
}