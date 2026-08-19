package com.mealiverit.api.verification;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 기본값 비활성. 이 Job(및 verification 패키지의 나머지 Step/Writer 설정들)이 무조건 컴포넌트
// 스캔되면, @EnableJdbcJobRepository가 만드는 JobRepository 인프라가 테스트용 H2 DB에서
// BATCH_JOB_INSTANCE_SEQ를 실제 SQL SEQUENCE로 찾으려다 실패한다(마이그레이션은 MySQL용
// 카운터 테이블 워크어라운드라 이름은 같아도 종류가 다름) - H2를 쓰는 기존 @SpringBootTest들이
// 전부 컨텍스트 로딩에서 깨졌었다(2026-08-19 확인). 이 Job은 아직 아무 데서도 트리거하는 곳이
// 없는 "Phase 3 선택 확장"이라, 실제로 쓸 준비가 됐을 때 app.consistency-verification.enabled=true로
// 켜는 방식으로 바꿨다 - Job/Step 로직 자체는 그대로 남아있다.
@Configuration
@ConditionalOnProperty(name = "app.consistency-verification.enabled", havingValue = "true")
@EnableBatchProcessing
@EnableJdbcJobRepository
public class ConsistencyVerificationJobConfig {

    @Bean
    public Job consistencyVerificationJob(
            JobRepository jobRepository,
            Step stockCheckStep,
            Step counterSyncStep,
            Step stateTransitionStep,
            Step membershipEligibilityStep,
            Step tierConsistencyStep
    ) {
        return new JobBuilder(
                "ConsistencyVerificationJob",
                jobRepository
        )
                .start(stockCheckStep)
                .next(counterSyncStep)
                .next(stateTransitionStep)
                .next(membershipEligibilityStep)
                .next(tierConsistencyStep)
                .build();
    }
}