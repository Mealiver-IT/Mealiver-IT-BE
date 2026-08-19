package com.mealiverit.api.verification;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import static com.mealiverit.api.verification.VerificationBatchConstants.PAGE_SIZE;

@Configuration
public class TierConsistencyStepConfig {

    public record TierMismatchRow(
            Long userId,
            String currentTier,
            int orderCount,
            String expectedTier
    ) {
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<TierMismatchRow> tierConsistencyReader(
            DataSource dataSource,
            @Value("#{jobParameters['targetMonth']}") String targetMonth
    ) throws Exception{
        YearMonth yearMonth = YearMonth.parse(targetMonth);

        LocalDateTime monthStart =
                yearMonth.atDay(1).atStartOfDay();

        LocalDateTime monthEnd =
                yearMonth.plusMonths(1)
                        .atDay(1)
                        .atStartOfDay();

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("monthStart", monthStart);
        parameters.put("monthEnd", monthEnd);

        return VerificationReaderFactory.create(
                dataSource,
                "tierConsistencyReader",
                "sql/verification/e_tier_orders_mismatch.sql",
                "user_id",
                (rs, rowNum) -> new TierMismatchRow(
                        rs.getLong("user_id"),
                        rs.getString("current_tier"),
                        rs.getInt("order_count"),
                        rs.getString("expected_tier")
                ),
                parameters
        );
    }

    @Bean
    public ItemProcessor<TierMismatchRow, VerificationViolation>
    tierConsistencyProcessor() {

        return row -> new VerificationViolation(
                "TIER_CONSISTENCY_MISMATCH",
                String.valueOf(row.userId()),
                "current_tier=%s, order_count=%d, expected_tier=%s"
                        .formatted(
                                row.currentTier(),
                                row.orderCount(),
                                row.expectedTier()
                        )
        );
    }

    @Bean
    public Step tierConsistencyStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<TierMismatchRow> tierConsistencyReader,
            ItemProcessor<TierMismatchRow, VerificationViolation> tierConsistencyProcessor,
            JdbcBatchItemWriter<VerificationViolation> verificationResultWriter
    ) {
        return new StepBuilder(
                "tierConsistencyStep",
                jobRepository
        )
                .<TierMismatchRow, VerificationViolation>chunk(
                        PAGE_SIZE,
                        transactionManager
                )
                .reader(tierConsistencyReader)
                .processor(tierConsistencyProcessor)
                .writer(verificationResultWriter)
                .build();
    }
}