package com.mealiverit.api.verification;

import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.transaction.PlatformTransactionManager;

import static com.mealiverit.api.verification.VerificationBatchConstants.PAGE_SIZE;

// StockCheckStepConfig / CounterSyncStepConfig / MembershipEligibilityStepConfig /
// TierConsistencyStepConfig / StateTransitionStepConfig 5개 Config에서 반복되던
// "reader -> processor -> writer, chunk 크기 PAGE_SIZE 고정" Step 조립 로직을 추출.
// reader/processor(도메인별 로직)는 각 Config에 그대로 남기고, 조립 부분만 공통화한다.
public final class VerificationStepFactory {

    private VerificationStepFactory() {
    }

    public static <I, O> Step chunkStep(
            String stepName,
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemReader<I> reader,
            ItemProcessor<I, O> processor,
            ItemWriter<O> writer,
            VerificationScanCountListener scanCountListener
    ) {
        return new StepBuilder(stepName, jobRepository)
                .<I, O>chunk(PAGE_SIZE, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .listener(scanCountListener)
                .build();
    }
}