package com.mealiverit.api.verification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

// sql/scripts/check_determinism.sh(수동 셸 스크립트, mysql CLI 필요)가 실제로는 어디서도
// 자동 실행되지 않던 문제를 애플리케이션 내부 배치로 대체한다 - 같은 검증 쿼리 7개를 두 번씩
// 돌려 결과 해시가 같은지 비교하는 것까지 셸 스크립트와 동일한 절차를 그대로 옮긴 것이다.
// (05_시스템설계.txt "검증 스크립트는 결과셋을 정렬 후 직렬화 -> SHA-256 해시" 요구사항 참고)
//
// ConsistencyVerificationJob(Spring Batch, app.consistency-verification.enabled)과는 목적이
// 다르다 - 그 쪽은 "위반이 실제로 있는가"를 300만 건 전체에서 찾아 리포트로 쌓는 것이고,
// 이 클래스는 "같은 쿼리를 두 번 돌리면 항상 같은 결과가 나오는가"(재실행 결정론성) 자체를
// 증명하는 것이다. 그래서 Spring Batch/JobRepository 인프라 없이 독립된 컴포넌트로 둔다 -
// 별도 플래그(app.determinism-check.enabled)로 따로 켜고 끈다.
@Component
@ConditionalOnProperty(name = "app.determinism-check.enabled", havingValue = "true", matchIfMissing = true)
public class SqlDeterminismCheckJob {

    private static final Logger log = LoggerFactory.getLogger(SqlDeterminismCheckJob.class);

    private static final String[] VERIFICATION_FILES = {
            "a_stock_overissue.sql",
            "b_counter_mismatch.sql",
            "c1_missing_log.sql",
            "c2_invalid_transition.sql",
            "c3_broken_chain.sql",
            "d_tier_violation.sql",
            "e_tier_orders_mismatch.sql",
    };

    // e_tier_orders_mismatch.sql만 :월시작/:월종료 named parameter를 쓴다(sql/verification/README.md,
    // TierConsistencyStepConfig와 동일 이름 - 다른 이름을 넘기면 NamedParameterJdbcTemplate이
    // 파라미터를 못 찾아 InvalidDataAccessApiUsageException을 던진다).
    private static final String TIER_ORDERS_FILE = "e_tier_orders_mismatch.sql";

    private static final Pattern LINE_COMMENT = Pattern.compile("--.*$", Pattern.MULTILINE);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SqlDeterminismCheckJob(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 결정론성 체크 결과 - 관리자 API(POST /api/admin/verification/run-determinism-check)가
    // 스케줄 실행을 하루 기다리지 않고도 같은 결과를 즉시 응답으로 돌려줄 수 있게 반환값을 둔다.
    public record Result(boolean allDeterministic, Map<String, Boolean> passByFile) {
    }

    // 정합성 검증 배치(03:00)보다 뒤, 등급 재산정 배치(매월 1일 04:00)와는 무관하게 매일 실행.
    @Scheduled(cron = "0 15 3 * * *")
    public Result run() {
        // MembershipTierBatchJob.runMonthly()와 동일하게 "지난 달" 기준 - e 파일의 대상 월도
        // 그 배치가 실제로 채운 값과 같은 기준이어야 위반 여부 판정이 의미가 있다.
        YearMonth targetMonth = YearMonth.now().minusMonths(1);
        Map<String, Object> tierOrdersParams = tierOrdersParams(targetMonth);

        Map<String, Boolean> passByFile = new LinkedHashMap<>();
        boolean allDeterministic = true;
        for (String file : VERIFICATION_FILES) {
            String sql = loadSql(file);
            Map<String, Object> params = TIER_ORDERS_FILE.equals(file) ? tierOrdersParams : Map.of();

            String hash1 = runAndHash(sql, params);
            String hash2 = runAndHash(sql, params);
            boolean pass = hash1.equals(hash2);
            passByFile.put(file, pass);
            allDeterministic &= pass;

            if (pass) {
                log.info("[determinism] {} PASS (두 번 실행 결과 해시 동일)", file);
            } else {
                log.error("[determinism] {} FAIL - 비결정론적 쿼리 의심 (run1={}, run2={})", file, hash1, hash2);
            }
        }

        if (allDeterministic) {
            log.info("[determinism] 전체 통과: 검증 쿼리 {}개 모두 결정론적", VERIFICATION_FILES.length);
        } else {
            log.error("[determinism] 일부 검증 쿼리가 비결정론적 - 검증 결과를 신뢰할 수 없음, 즉시 확인 필요");
        }

        return new Result(allDeterministic, passByFile);
    }

    private Map<String, Object> tierOrdersParams(YearMonth targetMonth) {
        LocalDateTime monthStart = targetMonth.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = targetMonth.plusMonths(1).atDay(1).atStartOfDay();
        Map<String, Object> params = new HashMap<>();
        params.put("월시작", monthStart);
        params.put("월종료", monthEnd);
        return params;
    }

    private String runAndHash(String sql, Map<String, Object> params) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);

        // 쿼리에 ORDER BY가 없으면 실행마다 행 순서가 달라질 수 있으므로(check_determinism.sh의
        // `sort`와 동일한 목적), 행을 정규화된 문자열로 바꾼 뒤 정렬해서 순서 차이를 흡수한다.
        List<String> normalizedRows = rows.stream()
                .map(this::normalizeRow)
                .sorted()
                .toList();

        String joined = String.join("\n", normalizedRows);
        return sha256(joined);
    }

    private String normalizeRow(Map<String, Object> row) {
        return row.values().stream()
                .map(v -> v == null ? "NULL" : v.toString())
                .collect(Collectors.joining("\t"));
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    // SqlFilePagingQueryProvider.removeTrailingSemicolon()과 동일한 이유 - 세미콜론이 남아있으면
    // 단일 문장 실행(PreparedStatement)이 "여러 문장"으로 오인돼 JDBC 드라이버가 거부한다.
    private String loadSql(String fileName) {
        try {
            ClassPathResource resource = new ClassPathResource("sql/verification/" + fileName);
            String raw = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            String withoutComments = LINE_COMMENT.matcher(raw).replaceAll("");
            String trimmed = withoutComments.trim();
            return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
        } catch (IOException e) {
            throw new IllegalStateException("검증 SQL 파일을 읽을 수 없습니다: " + fileName, e);
        }
    }
}
