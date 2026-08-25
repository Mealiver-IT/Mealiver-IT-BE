package com.mealiverit.api.verification.report;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class VerificationReportRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

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