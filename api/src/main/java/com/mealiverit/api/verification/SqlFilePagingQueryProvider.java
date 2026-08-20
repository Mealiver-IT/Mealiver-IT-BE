package com.mealiverit.api.verification;

import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.PagingQueryProvider;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlFilePagingQueryProvider implements PagingQueryProvider {

    private final String baseSql;
    private final String sortKey;
    private final Order sortOrder;

    public SqlFilePagingQueryProvider(
            String baseSql,
            String sortKey
    ) {
        this.baseSql = removeTrailingSemicolon(baseSql);
        this.sortKey = sortKey;
        this.sortOrder = Order.ASCENDING;
    }

    @Override
    public String generateFirstPageQuery(int pageSize) {
        return """
                SELECT *
                FROM (
                    %s
                ) verification_result
                ORDER BY verification_result.%s ASC
                LIMIT %d
                """.formatted(
                baseSql,
                sortKey,
                pageSize
        );
    }

    @Override
    public String generateRemainingPagesQuery(int pageSize) {
        return """
                SELECT *
                FROM (
                    %s
                ) verification_result
                WHERE verification_result.%s > :__paging_%s
                ORDER BY verification_result.%s ASC
                LIMIT %d
                """.formatted(
                baseSql,
                sortKey,
                sortKey,
                sortKey,
                pageSize
        );
    }

    @Override
    public int getParameterCount() {
        return countPagingParameters();
    }

    @Override
    public String getSortKeyPlaceHolder(String keyName) {
        return ":__paging_" + keyName;
    }

    @Override
    public Map<String, Order> getSortKeys() {
        Map<String, Order> result = new LinkedHashMap<>();
        result.put(sortKey, sortOrder);
        return result;
    }

    @Override
    public Map<String, Order> getSortKeysWithoutAliases() {
        return getSortKeys();
    }

    private static final Pattern NAMED_PARAM = Pattern.compile(":([\\p{L}\\p{N}_]+)");

    @Override
    public void init(DataSource dataSource) throws Exception {
        // named parameter(:월시작 등)를 ?로 치환해 순수 JDBC로도 dry-run 가능하게 만든다.
        // 실제 파라미터 값은 몰라도 SQL 문법/컬럼 존재 여부 검증에는 null 바인딩으로 충분하다.
        Matcher matcher = NAMED_PARAM.matcher(baseSql);
        String positionalSql = matcher.replaceAll("?");
        int paramCount = 0;
        while (matcher.find()) {
            paramCount++;
        }

        String validationSql = "SELECT * FROM (%s) verification_result_validation LIMIT 0"
                .formatted(positionalSql);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(validationSql)) {

            for (int i = 1; i <= paramCount; i++) {
                ps.setObject(i, null);
            }

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                boolean sortKeyExists = false;
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    if (metaData.getColumnLabel(i).equalsIgnoreCase(sortKey)) {
                        sortKeyExists = true;
                        break;
                    }
                }
                if (!sortKeyExists) {
                    throw new IllegalStateException(
                            "sortKey '%s' not found in query result columns".formatted(sortKey)
                    );
                }
            }
        }
    }

    @Override
    public boolean isUsingNamedParameters() {
        return true;
    }

    private int countPagingParameters() {
        return getSortKeys().size();
    }

    private static final Pattern LINE_COMMENT = Pattern.compile("--.*$", Pattern.MULTILINE);

    private static String removeTrailingSemicolon(String sql) {
        // 각 줄에서 -- 주석을 먼저 제거한 뒤, 진짜 마지막 SQL 토큰을 기준으로 세미콜론 여부를 판단한다.
        // (c3_broken_chain.sql처럼 세미콜론 뒤에 한글 주석 줄이 붙어있어도 정상 처리됨)
        String withoutComments = LINE_COMMENT.matcher(sql).replaceAll("");
        String trimmed = withoutComments.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}