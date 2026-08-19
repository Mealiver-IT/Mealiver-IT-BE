package com.mealiverit.api.verification;

import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.PagingQueryProvider;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private static String removeTrailingSemicolon(String sql) {
        String trimmed = sql.trim();

        if (trimmed.endsWith(";")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }
}