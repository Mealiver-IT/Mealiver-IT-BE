package com.mealiverit.api.verification;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableBatchProcessing
@EnableJdbcJobRepository
public class ConsistencyVerificationJobConfig {

    @Bean
    public Job consistencyVerificationJob(
            JobRepository jobRepository,
            Step stockCheckStep,
            Step counterSyncStep,
            Step stateTransitionStep,
            Step membershipEligibilityStep,
            Step tierConsistencyStep
    ) {
        return new JobBuilder(
                "ConsistencyVerificationJob",
                jobRepository
        )
                .start(stockCheckStep)
                .next(counterSyncStep)
                .next(stateTransitionStep)
                .next(membershipEligibilityStep)
                .next(tierConsistencyStep)
                .build();
    }
}