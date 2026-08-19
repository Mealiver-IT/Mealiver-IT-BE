package com.mealiverit.api.verification;

import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.PagingQueryProvider;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
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

    @Override
    public void init(DataSource dataSource) {
        // MySQL에서는 별도의 초기화가 필요하지 않다.
    }

    @Override
    public boolean isUsingNamedParameters() {
        return true;
    }

    private int countPagingParameters() {
        return 1;
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