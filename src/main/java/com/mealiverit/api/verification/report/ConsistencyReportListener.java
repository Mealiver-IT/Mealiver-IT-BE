package com.mealiverit.api.verification.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConsistencyReportListener
        implements JobExecutionListener {

    private final ConsistencyReportService consistencyReportService;

    @Override
    public void afterJob(
            JobExecution jobExecution
    ) {

        try {

            consistencyReportService.generate(
                    jobExecution
            );

        } catch (Exception e) {

            /*
             * 리포트 생성 실패가
             * 검증 Job 자체를 실패시키면 안 된다.
             */
            log.error(
                    "[ConsistencyReport] " +
                    "리포트 생성 실패. jobExecutionId={}",
                    jobExecution.getId(),
                    e
            );
        }
    }
}