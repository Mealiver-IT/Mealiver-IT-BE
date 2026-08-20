package com.mealiverit.api.verification;

import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import static com.mealiverit.api.verification.VerificationBatchConstants.INSERT_VERIFICATION_RESULT_SQL;

// ConsistencyVerificationJobConfig 참고 - 같은 조건으로 같이 켜고 끈다.
@Configuration
@ConditionalOnProperty(name = "app.consistency-verification.enabled", havingValue = "true")
public class VerificationWriterConfig {

	// @StepScope: "#{stepExecution.jobExecutionId}"로 실행 중인 Step의 job_execution_id를 늦은 바인딩으로
    // 받아온다. 같은 @Bean 정의를 4개 청크 Step이 재사용하되, 실행 시점마다 새 인스턴스로 생성된다.
    @Bean
    @org.springframework.batch.core.configuration.annotation.StepScope
    public JdbcBatchItemWriter<VerificationViolation> verificationResultWriter(
            DataSource dataSource,
            @Value("#{stepExecution.jobExecutionId}") String jobExecutionId
    ) {
        // jobExecutionId는 Step 실행 동안 고정값이므로, row마다 도는
        // itemPreparedStatementSetter 안이 아니라 빈 생성 시점(Step당 1회)에 한 번만 파싱한다.
        long parsedJobExecutionId = Long.parseLong(jobExecutionId);

        return new JdbcBatchItemWriterBuilder<VerificationViolation>()
                .dataSource(dataSource)
                .sql(INSERT_VERIFICATION_RESULT_SQL)
                .itemPreparedStatementSetter((item, ps) -> {
                    ps.setLong(1, parsedJobExecutionId);
                    ps.setString(2, item.checkType());
                    ps.setString(3, item.referenceId());
                    ps.setString(4, item.detail());
                })
                .build();
    }
}