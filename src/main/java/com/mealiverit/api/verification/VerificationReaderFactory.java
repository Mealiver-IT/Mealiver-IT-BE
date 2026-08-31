package com.mealiverit.api.verification;

import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.PagingQueryProvider;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.mealiverit.api.verification.VerificationBatchConstants.PAGE_SIZE;

public class VerificationReaderFactory {

    private VerificationReaderFactory() {
    }

    public static <T> JdbcPagingItemReader<T> create(
            DataSource dataSource,
            String readerName,
            String sqlPath,
            String sortKey,
            RowMapper<T> rowMapper,
            Map<String, Object> parameters
    )throws Exception {
        String sql = readSql(sqlPath);

        PagingQueryProvider queryProvider =
                new SqlFilePagingQueryProvider(sql, sortKey);

        return new JdbcPagingItemReaderBuilder<T>()
                .name(readerName)
                .dataSource(dataSource)
                .queryProvider(queryProvider)
                .parameterValues(parameters)
                .pageSize(PAGE_SIZE)
                .fetchSize(PAGE_SIZE)
                .rowMapper(rowMapper)
                .build();
    }

    private static String readSql(String sqlPath) {
        try {
            ClassPathResource resource =
                    new ClassPathResource(sqlPath);

            return StreamUtils.copyToString(
                    resource.getInputStream(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "검증 SQL 파일을 읽을 수 없습니다: " + sqlPath,
                    e
            );
        }
    }
}