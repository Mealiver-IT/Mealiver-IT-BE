package com.mealiverit.api.verification;

import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
public class StateTransitionStepConfig {

    public record MissingLogRow(
            Long id,
            String status
    ) {
    }

    public record InvalidTransitionRow(
            Long id,
            Long couponIssueId,
            String fromStatus,
            String toStatus
    ) {
    }

    public record BrokenChainRow(
            Long id,
            Long couponIssueId,
            String fromStatus,
            String toStatus,
            String prevToStatus
    ) {
    }

    @Bean
    public Step stateTransitionStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            DataSource dataSource,
            JdbcBatchItemWriter<VerificationViolation> verificationResultWriter
    ) {
        Tasklet tasklet = (contribution, chunkContext) -> {

            long jobExecutionId =
                    chunkContext
                            .getStepContext()
                            .getStepExecution()
                            .getJobExecutionId();

            runMissingLogCheck(
                    dataSource,
                    verificationResultWriter,
                    jobExecutionId
            );

            runInvalidTransitionCheck(
                    dataSource,
                    verificationResultWriter,
                    jobExecutionId
            );

            runBrokenChainCheck(
                    dataSource,
                    verificationResultWriter,
                    jobExecutionId
            );

            return RepeatStatus.FINISHED;
        };

        return new StepBuilder(
                "stateTransitionStep",
                jobRepository
        )
                .tasklet(tasklet, transactionManager)
                .build();
    }

    private void runMissingLogCheck(
            DataSource dataSource,
            JdbcBatchItemWriter<VerificationViolation> writer,
            long jobExecutionId
    ) throws Exception {

        JdbcPagingItemReader<MissingLogRow> reader =
                VerificationReaderFactory.create(
                        dataSource,
                        "stateMissingLogReader",
                        "sql/verification/c1_missing_log.sql",
                        "id",
                        (rs, rowNum) -> new MissingLogRow(
                                rs.getLong("id"),
                                rs.getString("status")
                        ),
                        Map.of()
                );

        drain(
                reader,
                writer,
                jobExecutionId,
                row -> new VerificationViolation(
                        "STATE_MISSING_LOG",
                        String.valueOf(row.id()),
                        "status=" + row.status()
                )
        );
    }

    private void runInvalidTransitionCheck(
            DataSource dataSource,
            JdbcBatchItemWriter<VerificationViolation> writer,
            long jobExecutionId
    ) throws Exception {

        JdbcPagingItemReader<InvalidTransitionRow> reader =
                VerificationReaderFactory.create(
                        dataSource,
                        "stateInvalidTransitionReader",
                        "sql/verification/c2_invalid_transition.sql",
                        "id",
                        (rs, rowNum) -> new InvalidTransitionRow(
                                rs.getLong("id"),
                                rs.getLong("coupon_issue_id"),
                                rs.getString("from_status"),
                                rs.getString("to_status")
                        ),
                        Map.of()
                );

        drain(
                reader,
                writer,
                jobExecutionId,
                row -> new VerificationViolation(
                        "STATE_INVALID_TRANSITION",
                        String.valueOf(row.couponIssueId()),
                        "log_id=%d, from=%s, to=%s"
                                .formatted(
                                        row.id(),
                                        row.fromStatus(),
                                        row.toStatus()
                                )
                )
        );
    }

    private void runBrokenChainCheck(
            DataSource dataSource,
            JdbcBatchItemWriter<VerificationViolation> writer,
            long jobExecutionId
    ) throws Exception {

        JdbcPagingItemReader<BrokenChainRow> reader =
                VerificationReaderFactory.create(
                        dataSource,
                        "stateBrokenChainReader",
                        "sql/verification/c3_broken_chain.sql",
                        "id",
                        (rs, rowNum) -> new BrokenChainRow(
                                rs.getLong("id"),
                                rs.getLong("coupon_issue_id"),
                                rs.getString("from_status"),
                                rs.getString("to_status"),
                                rs.getString("prev_to_status")
                        ),
                        Map.of()
                );

        drain(
                reader,
                writer,
                jobExecutionId,
                row -> new VerificationViolation(
                        "STATE_BROKEN_CHAIN",
                        String.valueOf(row.couponIssueId()),
                        "log_id=%d, from=%s, to=%s, prev_to_status=%s"
                                .formatted(
                                        row.id(),
                                        row.fromStatus(),
                                        row.toStatus(),
                                        row.prevToStatus()
                                )
                )
        );
    }

    private <T> void drain(
            JdbcPagingItemReader<T> reader,
            JdbcBatchItemWriter<VerificationViolation> writer,
            long jobExecutionId,
            java.util.function.Function<T, VerificationViolation> mapper
    ) throws Exception {

        reader.open(new ExecutionContext());

        try {
            T item;

            while ((item = reader.read()) != null) {

                VerificationViolation violation =
                        mapper.apply(item);

                writer.write(new Chunk<>(violation));
            }

        } finally {
            reader.close();
        }
    }
}