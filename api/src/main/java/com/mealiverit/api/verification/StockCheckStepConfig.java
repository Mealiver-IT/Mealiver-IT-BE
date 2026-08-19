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
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

import static com.mealiverit.api.verification.VerificationBatchConstants.PAGE_SIZE;

// ConsistencyVerificationJobConfig 참고 - 같은 조건으로 같이 켜고 끈다.
@Configuration
@ConditionalOnProperty(name = "app.consistency-verification.enabled", havingValue = "true")
public class StockCheckStepConfig {

    public record StockViolationRow(
            Long campaignId,
            int totalStock,
            int issuedCount,
            int overCount
    ) {
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<StockViolationRow> stockCheckReader(
            DataSource dataSource
    ) throws Exception {
        return VerificationReaderFactory.create(
                dataSource,
                "stockCheckReader",
                "sql/verification/a_stock_overissue.sql",
                "campaign_id",
                (rs, rowNum) -> new StockViolationRow(
                        rs.getLong("campaign_id"),
                        rs.getInt("total_stock"),
                        rs.getInt("issued_count"),
                        rs.getInt("over_count")
                ),
                Map.of()
        );
    }

    @Bean
    public ItemProcessor<StockViolationRow, VerificationViolation> stockCheckProcessor() {
        return row -> new VerificationViolation(
                "STOCK_OVERISSUE",
                String.valueOf(row.campaignId()),
                "total_stock=%d, issued_count=%d, over_count=%d"
                        .formatted(
                                row.totalStock(),
                                row.issuedCount(),
                                row.overCount()
                        )
        );
    }

    @Bean
    public Step stockCheckStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<StockViolationRow> stockCheckReader,
            ItemProcessor<StockViolationRow, VerificationViolation> stockCheckProcessor,
            JdbcBatchItemWriter<VerificationViolation> verificationResultWriter
    ) {
        return new StepBuilder("stockCheckStep", jobRepository)
                .<StockViolationRow, VerificationViolation>chunk(
                        PAGE_SIZE,
                        transactionManager
                )
                .reader(stockCheckReader)
                .processor(stockCheckProcessor)
                .writer(verificationResultWriter)
                .build();
    }
}