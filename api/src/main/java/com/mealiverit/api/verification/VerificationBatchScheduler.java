package com.mealiverit.api.verification;

import java.time.LocalDate;
import java.time.YearMonth;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VerificationBatchScheduler {

    private final JobLauncher jobLauncher;
    private final Job dailyConsistencyVerificationJob;
    private final Job tierOrdersMismatchJob;

    public VerificationBatchScheduler(
            JobLauncher jobLauncher,
            @Qualifier("dailyConsistencyVerificationJob") Job dailyConsistencyVerificationJob,
            @Qualifier("tierOrdersMismatchJob") Job tierOrdersMismatchJob
    ) {
        this.jobLauncher = jobLauncher;
        this.dailyConsistencyVerificationJob = dailyConsistencyVerificationJob;
        this.tierOrdersMismatchJob = tierOrdersMismatchJob;
    }

    // 매일 새벽 3시
    @Scheduled(cron = "0 0 3 * * *")
    public void runDailyVerification() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addLocalDate("runDate", LocalDate.now())
                .toJobParameters();
        jobLauncher.run(dailyConsistencyVerificationJob, params);
    }

    // 매월 1일 새벽 3시 — 전월 기준으로 검증
    @Scheduled(cron = "0 0 4 1 * *")
    public void runTierOrdersMismatchVerification() throws Exception {
        YearMonth targetMonth = YearMonth.now().minusMonths(1);
        JobParameters params = new JobParametersBuilder()
                .addString("targetMonth", targetMonth.toString())
                .toJobParameters();
        jobLauncher.run(tierOrdersMismatchJob, params);
    }
}