package com.mealiverit.api.verification;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

import static com.mealiverit.api.verification.VerificationBatchConstants.PAGE_SIZE;

// ConsistencyVerificationJobConfig 참고 - 같은 조건으로 같이 켜고 끈다.
@Configuration
@ConditionalOnProperty(name = "app.consistency-verification.enabled", havingValue = "true")
public class CounterSyncStepConfig {

    public record CounterViolationRow(
            Long campaignId,
            int counterIssued,
            int issuedCount,
            int remainingStock,
            int expectedRemaining
    ) {
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<CounterViolationRow> counterSyncReader(
            DataSource dataSource
    ) throws Exception {
        return VerificationReaderFactory.create(
                dataSource,
                "counterSyncReader",
                "sql/verification/b_counter_mismatch.sql",
                "campaign_id",
                (rs, rowNum) -> new CounterViolationRow(
                        rs.getLong("campaign_id"),
                        rs.getInt("counter_issued"),
                        rs.getInt("issued_count"),
                        rs.getInt("remaining_stock"),
                        rs.getInt("expected_remaining")
                ),
                Map.of()
        );
    }

    @Bean
    public ItemProcessor<CounterViolationRow, VerificationViolation> counterSyncProcessor() {
        return row -> new VerificationViolation(
                "COUNTER_MISMATCH",
                String.valueOf(row.campaignId()),
                "counter_issued=%d, issued_count=%d, remaining_stock=%d, expected_remaining=%d"
                        .formatted(
                                row.counterIssued(),
                                row.issuedCount(),
                                row.remainingStock(),
                                row.expectedRemaining()
                        )
        );
    }
    
    @Bean
    public VerificationScanCountListener counterSyncScanCountListener(
            JdbcTemplate jdbcTemplate
    ) {
        return new VerificationScanCountListener(
                jdbcTemplate,
                "SELECT COUNT(*) FROM campaign"
        );
    }

    @Bean
    public Step counterSyncStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<CounterViolationRow> counterSyncReader,
            ItemProcessor<CounterViolationRow, VerificationViolation> counterSyncProcessor,
            JdbcBatchItemWriter<VerificationViolation> verificationResultWriter,
            VerificationScanCountListener counterSyncScanCountListener
    ) {
        return VerificationStepFactory.chunkStep(
                "counterSyncStep",
                jobRepository,
                transactionManager,
                counterSyncReader,
                counterSyncProcessor,
                verificationResultWriter,
                counterSyncScanCountListener
                
        );
    }
}