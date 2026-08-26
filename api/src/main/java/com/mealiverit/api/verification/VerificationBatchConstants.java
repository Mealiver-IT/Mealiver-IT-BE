package com.mealiverit.api.verification;

public final class VerificationBatchConstants {

    // 05_시스템설계.txt 1.2절: 300만 건을 10,000건씩 페이징 — 리더 pageSize와 청크 커밋 크기를 동일하게 맞춘다.
    public static final int PAGE_SIZE = 10_000;

    public static final String INSERT_VERIFICATION_RESULT_SQL =
            "INSERT INTO verification_result " +
            "(job_execution_id, check_type, reference_id, detail, detected_at) " +
            "VALUES (?, ?, ?, ?, ?)";

    private VerificationBatchConstants() {
    }
}