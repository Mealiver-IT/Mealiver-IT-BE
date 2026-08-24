package com.mealiverit.api.verification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.consistency-verification.enabled", havingValue = "true")
public class VerificationBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(VerificationBatchScheduler.class);

    private final JobOperator jobOperator;
    private final Job dailyConsistencyVerificationJob;
    private final Job tierOrdersMismatchJob;

    public VerificationBatchScheduler(
            JobOperator jobOperator,
            @Qualifier("dailyConsistencyVerificationJob") Job dailyConsistencyVerificationJob,
            @Qualifier("tierOrdersMismatchJob") Job tierOrdersMismatchJob
    ) {
        this.jobOperator = jobOperator;
        this.dailyConsistencyVerificationJob = dailyConsistencyVerificationJob;
        this.tierOrdersMismatchJob = tierOrdersMismatchJob;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void runDaily() {
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("runDate", LocalDate.now())
                .addLocalDateTime("runAt", LocalDateTime.now())
                .toJobParameters();
        try {
            JobExecution execution = jobOperator.start(dailyConsistencyVerificationJob, jobParameters);
            log.info("DailyConsistencyVerificationJob 시작: jobExecutionId={}", execution.getId());
        } catch (JobInstanceAlreadyCompleteException | JobExecutionAlreadyRunningException
                | InvalidJobParametersException | JobRestartException e) {
            log.error("DailyConsistencyVerificationJob 실행 실패", e);
        }
    }

    @Scheduled(cron = "0 00 4 1 * *")
//    @Scheduled(cron = "0 * * * * *")
    public void runMonthly() {
        run(YearMonth.now().minusMonths(1));
    }

    public void run(YearMonth targetMonth) {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("targetMonth", targetMonth.toString())
                .addLocalDateTime("runAt", LocalDateTime.now())
                .toJobParameters();
        try {
            JobExecution execution = jobOperator.start(tierOrdersMismatchJob, jobParameters);
            log.info("TierOrdersMismatchJob 시작: targetMonth={}, jobExecutionId={}",
                    targetMonth, execution.getId());
        } catch (JobInstanceAlreadyCompleteException | JobExecutionAlreadyRunningException
                | InvalidJobParametersException | JobRestartException e) {
            log.error("TierOrdersMismatchJob 실행 실패: targetMonth={}", targetMonth, e);
        }
    }
}