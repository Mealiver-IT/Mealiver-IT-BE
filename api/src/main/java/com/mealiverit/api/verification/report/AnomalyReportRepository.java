package com.mealiverit.api.verification.report;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class AnomalyReportRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AnomalyReportRepository(
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * JobExecution별 검증 항목 이상 건수 조회
     *
     * 이상이 0건인 CheckType도 0으로 포함한다.
     */
    public Map<CheckType, Long> countByJobExecutionId(
            long jobExecutionId
    ) {

        String sql = """
            SELECT check_type, COUNT(*) AS cnt
            FROM verification_result
            WHERE job_execution_id = :jobExecutionId
            GROUP BY check_type
            """;

        Map<CheckType, Long> result =
                new LinkedHashMap<>();

        // 모든 검증 항목을 먼저 0건으로 초기화
        for (CheckType checkType : CheckType.values()) {
            result.put(checkType, 0L);
        }

        jdbcTemplate.query(
                sql,
                new MapSqlParameterSource(
                        "jobExecutionId",
                        jobExecutionId
                ),
                rs -> {

                    CheckType checkType =
                            CheckType.fromCode(
                                    rs.getString("check_type")
                            );

                    result.put(
                            checkType,
                            rs.getLong("cnt")
                    );
                }
        );

        return result;
    }

    /**
     * JobExecution에서 발견된 이상 상세 조회
     */
    public List<AnomalyDetail> findDetailsByJobExecutionId(
            long jobExecutionId
    ) {

        String sql = """
            SELECT
                check_type,
                reference_id,
                detail,
                detected_at
            FROM verification_result
            WHERE job_execution_id = :jobExecutionId
            ORDER BY detected_at, id
            """;

        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource(
                        "jobExecutionId",
                        jobExecutionId
                ),
                (rs, rowNum) ->
                        new AnomalyDetail(
                                CheckType.fromCode(
                                        rs.getString("check_type")
                                ),
                                rs.getString("reference_id"),
                                rs.getString("detail"),
                                rs.getTimestamp(
                                        "detected_at"
                                ).toLocalDateTime()
                        )
        );
    }
}