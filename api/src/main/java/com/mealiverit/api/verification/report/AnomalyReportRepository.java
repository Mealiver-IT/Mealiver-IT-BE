package com.mealiverit.api.verification.report;

import com.mealiverit.api.verification.report.CheckType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class AnomalyReportRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AnomalyReportRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<CheckType, Long> countByJobExecutionId(long jobExecutionId) {
        String sql = """
            SELECT check_type, COUNT(*) AS cnt
            FROM verification_result
            WHERE job_execution_id = :jobExecutionId
            GROUP BY check_type
            """;

        Map<CheckType, Long> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("jobExecutionId", jobExecutionId),
                rs -> {
                    result.put(CheckType.fromCode(rs.getString("check_type")), rs.getLong("cnt"));
                }
            );
        return result;
    }
}