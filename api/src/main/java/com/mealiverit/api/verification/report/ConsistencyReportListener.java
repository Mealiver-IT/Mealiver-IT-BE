package com.mealiverit.api.verification.report;

import com.mealiverit.api.verification.report.CheckType;
import com.mealiverit.api.verification.report.AnomalyReportRepository;
import com.mealiverit.api.verification.report.ConsistencyReport;
import com.mealiverit.api.verification.report.SlackNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConsistencyReportListener implements JobExecutionListener {

    private final AnomalyReportRepository anomalyReportRepository;
    private final SlackNotifier slackNotifier;

    @Override
    public void afterJob(JobExecution jobExecution) {
        long jobExecutionId = jobExecution.getId();

        Map<CheckType, Long> anomalyCounts =
            anomalyReportRepository.countByJobExecutionId(jobExecutionId);

        List<String> failedSteps = jobExecution.getStepExecutions().stream()
            .filter(se -> se.getStatus().isUnsuccessful())
            .map(StepExecution::getStepName)
            .toList();

        ConsistencyReport report = new ConsistencyReport(
            jobExecutionId,
            jobExecution.getJobInstance().getJobName(),
            jobExecution.getStartTime(),
            jobExecution.getStatus(),
            anomalyCounts,
            failedSteps
        );

        try {
            slackNotifier.send(report);
        } catch (Exception e) {
            log.error("[ConsistencyReport] Slack 전송 실패. jobExecutionId={}", jobExecutionId, e);
        }
    }
}