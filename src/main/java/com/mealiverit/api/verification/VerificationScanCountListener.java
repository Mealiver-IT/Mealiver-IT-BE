package com.mealiverit.api.verification;


import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Step 시작 전, 이 Step이 검증 대상으로 삼는 전체 건수를 미리 세어
 * StepExecution의 ExecutionContext에 저장한다.
 *
 * readCount는 "위반으로 걸러진 row 수"만 세기 때문에,
 * "총 몇 건을 검사했는지"는 별도로 카운트해야 한다.
 */
public class VerificationScanCountListener implements StepExecutionListener {

    public static final String SCANNED_COUNT_KEY = "scannedCount";

    private final JdbcTemplate jdbcTemplate;
    private final String countSql;

    public VerificationScanCountListener(
            JdbcTemplate jdbcTemplate,
            String countSql
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.countSql = countSql;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {

        Long count = jdbcTemplate.queryForObject(
                countSql,
                Long.class
        );

        stepExecution.getExecutionContext()
                .putLong(
                        SCANNED_COUNT_KEY,
                        count != null ? count : 0L
                );
    }
}