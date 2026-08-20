package com.mealiverit.api.verification;

import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

import static com.mealiverit.api.verification.VerificationBatchConstants.PAGE_SIZE;

// ConsistencyVerificationJobConfig 참고 - 같은 조건으로 같이 켜고 끈다.
//
// 2026-08-19: 기존엔 c-1/c-2/c-3 세 체크를 tasklet 하나(트랜잭션 하나)로 묶어서 처리했는데,
// 다른 4개 Step은 전부 chunk 기반(PAGE_SIZE 커밋)인 것과 어긋나고, 커넥션 하나가 세 체크
// 스캔 내내 붙잡혀 있는 구조라 HikariCP 풀 부족 이슈(부하테스트 기록, application.properties
// 참고)와 같은 종류의 리스크였다. 또한 tasklet 안에서 하나라도 실패하면 이미 찾은 위반사항까지
// 전부 롤백되는 문제도 있었다. 세 체크를 각각 독립된 chunk Step으로 분리해 해결.
@Configuration
@ConditionalOnProperty(name = "app.consistency-verification.enabled", havingValue = "true")
public class StateTransitionStepConfig {

    public record MissingLogRow(
            Long id,
            String status
    ) {
    }

    public record InvalidTransitionRow(
            Long id,
            Long couponIssueId,
            String fromStatus,
            String toStatus
    ) {
    }

    public record BrokenChainRow(
            Long id,
            Long couponIssueId,
            String fromStatus,
            String toStatus,
            String prevToStatus
    ) {
    }

    // ---- missing-log (c-1) ----

    @Bean
    public JdbcPagingItemReader<MissingLogRow> missingLogReader(DataSource dataSource) throws Exception {
        return VerificationReaderFactory.create(
                dataSource,
                "stateMissingLogReader",
                "sql/verification/c1_missing_log.sql",
                "id",
                (rs, rowNum) -> new MissingLogRow(
                        rs.getLong("id"),
                        rs.getString("status")
                ),
                Map.of()
        );
    }

    @Bean
    public ItemProcessor<MissingLogRow, VerificationViolation> missingLogProcessor() {
        return row -> new VerificationViolation(
                "STATE_MISSING_LOG",
                String.valueOf(row.id()),
                "status=" + row.status()
        );
    }

    @Bean
    public Step missingLogStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<MissingLogRow> missingLogReader,
            ItemProcessor<MissingLogRow, VerificationViolation> missingLogProcessor,
            JdbcBatchItemWriter<VerificationViolation> verificationResultWriter
    ) {
        return VerificationStepFactory.chunkStep(
                "missingLogStep",
                jobRepository,
                transactionManager,
                missingLogReader,
                missingLogProcessor,
                verificationResultWriter
        );
    }

    // ---- invalid-transition (c-2) ----

    @Bean
    public JdbcPagingItemReader<InvalidTransitionRow> invalidTransitionReader(DataSource dataSource) throws Exception {
        return VerificationReaderFactory.create(
                dataSource,
                "stateInvalidTransitionReader",
                "sql/verification/c2_invalid_transition.sql",
                "id",
                (rs, rowNum) -> new InvalidTransitionRow(
                        rs.getLong("id"),
                        rs.getLong("coupon_issue_id"),
                        rs.getString("from_status"),
                        rs.getString("to_status")
                ),
                Map.of()
        );
    }

    @Bean
    public ItemProcessor<InvalidTransitionRow, VerificationViolation> invalidTransitionProcessor() {
        return row -> new VerificationViolation(
                "STATE_INVALID_TRANSITION",
                String.valueOf(row.couponIssueId()),
                "log_id=%d, from=%s, to=%s"
                        .formatted(row.id(), row.fromStatus(), row.toStatus())
        );
    }

    @Bean
    public Step invalidTransitionStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<InvalidTransitionRow> invalidTransitionReader,
            ItemProcessor<InvalidTransitionRow, VerificationViolation> invalidTransitionProcessor,
            JdbcBatchItemWriter<VerificationViolation> verificationResultWriter
    ) {
    	return VerificationStepFactory.chunkStep(
    	        "invalidTransitionStep",
    	        jobRepository,
    	        transactionManager,
    	        invalidTransitionReader,
    	        invalidTransitionProcessor,
    	        verificationResultWriter
    	);
    }

    // ---- broken-chain (c-3) ----

    @Bean
    public JdbcPagingItemReader<BrokenChainRow> brokenChainReader(DataSource dataSource) throws Exception {
        return VerificationReaderFactory.create(
                dataSource,
                "stateBrokenChainReader",
                "sql/verification/c3_broken_chain.sql",
                "id",
                (rs, rowNum) -> new BrokenChainRow(
                        rs.getLong("id"),
                        rs.getLong("coupon_issue_id"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("prev_to_status")
                ),
                Map.of()
        );
    }

    @Bean
    public ItemProcessor<BrokenChainRow, VerificationViolation> brokenChainProcessor() {
        return row -> new VerificationViolation(
                "STATE_BROKEN_CHAIN",
                String.valueOf(row.couponIssueId()),
                "log_id=%d, from=%s, to=%s, prev_to_status=%s"
                        .formatted(row.id(), row.fromStatus(), row.toStatus(), row.prevToStatus())
        );
    }

    @Bean
    public Step brokenChainStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<BrokenChainRow> brokenChainReader,
            ItemProcessor<BrokenChainRow, VerificationViolation> brokenChainProcessor,
            JdbcBatchItemWriter<VerificationViolation> verificationResultWriter
    ) {
    	return VerificationStepFactory.chunkStep(
    	        "brokenChainStep",
    	        jobRepository,
    	        transactionManager,
    	        brokenChainReader,
    	        brokenChainProcessor,
    	        verificationResultWriter
    	);
    }
}