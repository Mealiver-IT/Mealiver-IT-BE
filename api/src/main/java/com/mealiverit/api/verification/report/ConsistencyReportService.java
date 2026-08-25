package com.mealiverit.api.verification.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsistencyReportService {

	private final AnomalyReportRepository anomalyReportRepository;
	private final JobCheckTypeResolver jobCheckTypeResolver;
    private final VerificationReportRepository verificationReportRepository;
    private final NotionReportGenerator notionReportGenerator;
    private final SlackNotifier slackNotifier;

    public void generate(
            JobExecution jobExecution
    ) {

        long jobExecutionId =
                jobExecution.getId();

        // 1. 검증 항목별 이상 건수
     // 1. 검증 항목별 이상 건수
        String jobName =
                jobExecution
                        .getJobInstance()
                        .getJobName();

        Set<CheckType> applicableTypes =
                jobCheckTypeResolver.resolve(jobName);

        Map<CheckType, Long> anomalyCounts =
                anomalyReportRepository
                        .countByJobExecutionId(
                                jobExecutionId,
                                applicableTypes
                        );

        // 2. 이상 상세
        List<AnomalyDetail> anomalyDetails =
                anomalyReportRepository
                        .findDetailsByJobExecutionId(
                                jobExecutionId
                        );

        // 3. Step 실행 통계
        List<StepExecutionSummary> stepExecutions =
                jobExecution
                        .getStepExecutions()
                        .stream()
                        .map(this::toSummary)
                        .toList();

        // 4. 실패 Step
        List<String> failedSteps =
                jobExecution
                        .getStepExecutions()
                        .stream()
                        .filter(step ->
                                step.getStatus()
                                        .isUnsuccessful()
                        )
                        .map(StepExecution::getStepName)
                        .toList();

        // 5. 최종 Report 객체 생성
        ConsistencyReport report =
                new ConsistencyReport(
                        jobExecutionId,
                        jobExecution
                                .getJobInstance()
                                .getJobName(),
                        jobExecution.getStartTime(),
                        jobExecution.getEndTime(),
                        jobExecution.getStatus(),
                        anomalyCounts,
                        stepExecutions,
                        failedSteps,
                        anomalyDetails
                );

     // 6. Notion 페이지 생성
        String reportUrl =
                notionReportGenerator.generate(
                        report
                );

        // 7. DB에 실행 요약 저장
        verificationReportRepository.save(
                report,
                reportUrl
        );

        // 8. Slack 알림
        try {

            slackNotifier.send(
                    report,
                    reportUrl
            );

        } catch (Exception e) {

            log.error(
                    "[ConsistencyReport] Slack 전송 실패. " +
                    "jobExecutionId={}",
                    jobExecutionId,
                    e
            );
        }
    }

    private StepExecutionSummary toSummary(
            StepExecution step
    ) {

        return new StepExecutionSummary(
                step.getStepName(),
                step.getStatus().name(),
                step.getStartTime(),
                step.getEndTime(),
                step.getReadCount(),
                step.getWriteCount(),
                step.getFilterCount(),
                step.getReadSkipCount(),
                step.getProcessSkipCount(),
                step.getWriteSkipCount()
        );
    }
}