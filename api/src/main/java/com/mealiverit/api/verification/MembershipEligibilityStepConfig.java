package com.mealiverit.api.verification;

import com.mealiverit.entity.user.MembershipTier;
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
public class MembershipEligibilityStepConfig {

    public record TierViolationRow(
            Long id,
            Long userId,
            String tierAtIssue,
            String requiredTier,
            Long campaignId,
            java.time.LocalDateTime issuedAt
    ) {
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<TierViolationRow> membershipEligibilityReader(
            DataSource dataSource
    ) throws Exception {
        return VerificationReaderFactory.create(
                dataSource,
                "membershipEligibilityReader",
                "sql/verification/d_tier_violation.sql",
                "id",
                (rs, rowNum) -> new TierViolationRow(
                        rs.getLong("id"),
                        rs.getLong("user_id"),
                        rs.getString("tier_at_issue"),
                        rs.getString("required_tier"),
                        rs.getLong("campaign_id"),
                        rs.getTimestamp("issued_at").toLocalDateTime()
                ),
                Map.of()
        );
    }

    @Bean
    public ItemProcessor<TierViolationRow, VerificationViolation>
    membershipEligibilityProcessor() {

        return row -> new VerificationViolation(
                "TIER_ELIGIBILITY_VIOLATION",
                String.valueOf(row.id()),
                "user_id=%d, tier_at_issue=%s, required_tier=%s, campaign_id=%d, issued_at=%s"
                        .formatted(
                                row.userId(),
                                row.tierAtIssue(),
                                row.requiredTier(),
                                row.campaignId(),
                                row.issuedAt()
                        )
        );
    }

    @Bean
    public Step membershipEligibilityStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<TierViolationRow> membershipEligibilityReader,
            ItemProcessor<TierViolationRow, VerificationViolation> membershipEligibilityProcessor,
            JdbcBatchItemWriter<VerificationViolation> verificationResultWriter
    ) {
        return VerificationStepFactory.chunkStep(
                "membershipEligibilityStep",
                jobRepository,
                transactionManager,
                membershipEligibilityReader,
                membershipEligibilityProcessor,
                verificationResultWriter
        );
    }
}