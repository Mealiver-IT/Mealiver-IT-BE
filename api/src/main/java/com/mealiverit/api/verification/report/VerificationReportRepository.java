package com.mealiverit.api.verification.report;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class VerificationReportRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 관리자 대시보드용 - 이 Job의 가장 최근 실행 1건. save()가 매 JobExecution마다 기록해두는
    // verification_report를 그대로 읽는다(예전엔 BATCH_JOB_EXECUTION을 직접 조회했는데, 이 테이블이
    // 생기면서 total_violation_count/duration_ms가 이미 계산돼 저장돼있어 더 간단하다).
    public Optional<LatestReport> findLatestByJobName(String jobName) {
        String sql = """
            SELECT job_execution_id, job_name, started_at, ended_at, duration_ms, total_violation_count, status
            FROM verification_report
            WHERE job_name = :jobName
            ORDER BY id DESC
            LIMIT 1
            """;

        List<LatestReport> rows = jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("jobName", jobName),
                (rs, rowNum) -> new LatestReport(
                        rs.getLong("job_execution_id"),
                        rs.getString("job_name"),
                        rs.getObject("started_at", LocalDateTime.class),
                        rs.getObject("ended_at", LocalDateTime.class),
                        rs.getLong("duration_ms"),
                        rs.getLong("total_violation_count"),
                        rs.getString("status")
                )
        );
        return rows.stream().findFirst();
    }

    public record LatestReport(
            long jobExecutionId,
            String jobName,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            long durationMs,
            long totalViolationCount,
            String status
    ) {
    }

    public void save(
            ConsistencyReport report,
            String reportFilePath
    ) {

        String sql = """
            INSERT INTO verification_report (
                job_execution_id,
                job_name,
                started_at,
                ended_at,
                duration_ms,
                total_violation_count,
                status,
                report_file_path
            )
            VALUES (
                :jobExecutionId,
                :jobName,
                :startedAt,
                :endedAt,
                :durationMs,
                :totalViolationCount,
                :status,
                :reportFilePath
            )
            """;

        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue(
                                "jobExecutionId",
                                report.jobExecutionId()
                        )
                        .addValue(
                                "jobName",
                                report.jobName()
                        )
                        .addValue(
                                "startedAt",
                                report.startTime()
                        )
                        .addValue(
                                "endedAt",
                                report.endTime()
                        )
                        .addValue(
                                "durationMs",
                                report.durationMillis()
                        )
                        .addValue(
                                "totalViolationCount",
                                report.totalViolationCount()
                        )
                        .addValue(
                                "status",
                                report.status().name()
                        )
                        .addValue(
                                "reportFilePath",
                                reportFilePath
                        );

        jdbcTemplate.update(sql, params);
    }
}