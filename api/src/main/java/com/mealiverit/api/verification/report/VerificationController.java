package com.mealiverit.api.verification.report;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.common.response.ApiResponse;
import com.mealiverit.api.verification.report.VerificationReportRepository.LatestReport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// 관리자 대시보드용 검증(정합성) 결과 조회 + 수동 실행. Slack/노션으로 알림이 나가는 배치는 실제로
// 2개다: DailyConsistencyVerificationJob(일간, 6개 체크) 와 TierOrdersMismatchJob(월간, 계급-주문
// 정합성 1개 체크) - 서로 다른 JobExecution 계열이라 latest()가 둘 다 따로 조회해서 반환한다.
// "가장 최근 실행 요약" 조회는 verification_report 테이블(ConsistencyReportService.generate()가
// 매 JobExecution 종료 시 기록)을 읽는다 - 체크타입별 세부 건수는 여전히 verification_result를
// 원본으로 하는 AnomalyReportRepository.countByJobExecutionId()로 조회한다.
// 실행(run*)은 실제 Job Bean이 있어야만 가능하므로 JobOperator/Job을 Optional로 주입받는다 -
// app.consistency-verification.enabled가 꺼진 인스턴스에서도 컨트롤러 자체는 여전히 뜨되(latest
// 조회는 계속 가능), run 호출 시 503으로 명확히 안내한다.
@RestController
public class VerificationController {

    private static final String DAILY_JOB_NAME = "DailyConsistencyVerificationJob";
    private static final String TIER_JOB_NAME = "TierOrdersMismatchJob";

    private final VerificationReportRepository verificationReportRepository;
    private final AnomalyReportRepository anomalyReportRepository;
    private final JobCheckTypeResolver jobCheckTypeResolver;
    private final Optional<JobOperator> jobOperator;
    private final Optional<Job> dailyConsistencyVerificationJob;
    private final Optional<Job> tierOrdersMismatchJob;

    public VerificationController(
            VerificationReportRepository verificationReportRepository,
            AnomalyReportRepository anomalyReportRepository,
            JobCheckTypeResolver jobCheckTypeResolver,
            Optional<JobOperator> jobOperator,
            @Qualifier("dailyConsistencyVerificationJob") Optional<Job> dailyConsistencyVerificationJob,
            @Qualifier("tierOrdersMismatchJob") Optional<Job> tierOrdersMismatchJob
    ) {
        this.verificationReportRepository = verificationReportRepository;
        this.anomalyReportRepository = anomalyReportRepository;
        this.jobCheckTypeResolver = jobCheckTypeResolver;
        this.jobOperator = jobOperator;
        this.dailyConsistencyVerificationJob = dailyConsistencyVerificationJob;
        this.tierOrdersMismatchJob = tierOrdersMismatchJob;
    }

    @GetMapping("/api/admin/verification/latest")
    public ApiResponse<VerificationOverviewResponse> latest() {
        return ApiResponse.success(new VerificationOverviewResponse(
                latestFor(DAILY_JOB_NAME),
                latestFor(TIER_JOB_NAME)
        ));
    }

    @PostMapping("/api/admin/verification/run")
    public ApiResponse<Map<String, Object>> run() {
        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDate("runDate", LocalDate.now())
                .addLocalDateTime("runAt", LocalDateTime.now())
                .toJobParameters();
        return startJob(dailyConsistencyVerificationJob, jobParameters);
    }

    // TierOrdersMismatchJob은 targetMonth가 필수 파라미터라(ConsistencyVerificationJobConfig의
    // tierOrdersMismatchJobParametersValidator) run()과 파라미터 구성이 다르다.
    // VerificationBatchScheduler.runMonthly()와 동일하게 기본은 "지난달" 기준.
    @PostMapping("/api/admin/verification/run-tier-monthly")
    public ApiResponse<Map<String, Object>> runTierMonthly() {
        YearMonth targetMonth = YearMonth.now().minusMonths(1);
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("targetMonth", targetMonth.toString())
                .addLocalDateTime("runAt", LocalDateTime.now())
                .toJobParameters();
        return startJob(tierOrdersMismatchJob, jobParameters);
    }

    private ApiResponse<Map<String, Object>> startJob(Optional<Job> job, JobParameters jobParameters) {
        Job resolvedJob = job.orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_BATCH_DISABLED));
        JobOperator operator = jobOperator.orElseThrow(() -> new BusinessException(ErrorCode.VERIFICATION_BATCH_DISABLED));

        try {
            JobExecution execution = operator.start(resolvedJob, jobParameters);
            return ApiResponse.success(Map.of("jobExecutionId", execution.getId()));
        } catch (JobExecutionAlreadyRunningException e) {
            throw new BusinessException(ErrorCode.VERIFICATION_ALREADY_RUNNING);
        } catch (JobInstanceAlreadyCompleteException | InvalidJobParametersException | JobRestartException e) {
            throw new BusinessException(ErrorCode.VERIFICATION_START_FAILED);
        }
    }

    private VerificationSummaryResponse latestFor(String jobName) {
        return verificationReportRepository.findLatestByJobName(jobName)
                .map(this::toSummary)
                .orElseGet(VerificationSummaryResponse::notRunYet);
    }

    private VerificationSummaryResponse toSummary(LatestReport report) {
        Set<CheckType> applicableTypes = jobCheckTypeResolver.resolve(report.jobName());
        Map<CheckType, Long> counts = anomalyReportRepository.countByJobExecutionId(report.jobExecutionId(), applicableTypes);
        Map<String, Long> countsByName = counts.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));

        return new VerificationSummaryResponse(
                true,
                report.jobExecutionId(),
                report.startedAt(),
                report.endedAt(),
                report.durationMs() / 1000,
                report.status(),
                report.totalViolationCount(),
                countsByName
        );
    }
}
