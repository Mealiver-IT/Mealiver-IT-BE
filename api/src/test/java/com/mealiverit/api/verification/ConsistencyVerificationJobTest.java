package com.mealiverit.api.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 목적: ConsistencyVerificationJob(Spring Batch, dev 2026-08-19 병합)이 원본 검증 SQL
// (sql/verification/*.sql, check_zero_anomalies.sh가 쓰는 것과 동일 파일)과 같은 결과를
// 배치 파이프라인을 통해서도 내는지 확인한다. 실제 300만 건 원격 DB가 아니라 Testcontainers
// 로컬 MySQL에 소규모 데이터를 직접 심어서, "정상 데이터 0건 + 오염 데이터 정확히 N건 탐지"라는
// 같은 양방향 원칙을 배치 잡 레벨에서 재현한다.
//
// app.consistency-verification.enabled=true는 이 테스트 클래스 안에서만 켠다 — 전역
// application.properties에 넣으면 H2 기반 다른 @SpringBootTest들이 BATCH_JOB_INSTANCE_SEQ
// 시퀀스 문제로 깨진다(ConsistencyVerificationJobConfig 클래스 주석, 2026-08-19 확인 이력).
@SpringBootTest(properties = "app.consistency-verification.enabled=true")
@Testcontainers
class ConsistencyVerificationJobTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("consistencyVerificationJob")
    private Job consistencyVerificationJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 정상_데이터는_0건_오염된_재고초과_캠페인만_정확히_탐지한다() throws Exception {
        // Arrange — 캠페인 2개: 재고 정상(clean) / 재고 초과(dirty, 7명 발급인데 total_stock=5).
        // remaining_stock을 -2로 둔 이유: total_stock(5) - remaining_stock(-2) = 7 = issued_count와
        // 맞아떨어지게 해서 counterSyncStep(b_counter_mismatch)은 통과시키고 stockCheckStep
        // (a_stock_overissue)만 단독으로 걸리도록 격리했다 — dirty_data_seed.sql이 케이스별로
        // 격리하는 것과 같은 원칙.
        LocalDateTime now = LocalDateTime.now();

        Long cleanCampaignId = insertCampaign("clean-campaign", 10, 10);
        Long dirtyCampaignId = insertCampaign("dirty-campaign-overissue", 5, -2);

        for (int i = 1; i <= 7; i++) {
            Long userId = insertUser("dirty_user_" + i);
            insertIssuedCoupon(dirtyCampaignId, userId, "DIRTY-CODE-" + i, "DIRTY-IDEMP-" + i, now);
        }

        // Act
        JobExecution execution = jobLauncher.run(
                consistencyVerificationJob,
                new JobParametersBuilder()
                        .addString("targetMonth", YearMonth.now().toString())
                        .addLong("runAt", System.currentTimeMillis())
                        .toJobParameters()
        );

        // Assert
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<Map<String, Object>> violations = jdbcTemplate.queryForList(
                "SELECT check_type, reference_id, detail FROM verification_result ORDER BY check_type"
        );

        assertThat(violations)
                .as("clean 캠페인은 어떤 검증에도 안 걸리고, dirty 캠페인은 재고초과 1건만 걸려야 한다")
                .hasSize(1);
        assertThat(violations.get(0).get("check_type")).isEqualTo("STOCK_OVERISSUE");
        assertThat(violations.get(0).get("reference_id")).isEqualTo(String.valueOf(dirtyCampaignId));
        assertThat((String) violations.get(0).get("detail")).contains("over_count=2");
        assertThat(cleanCampaignId).isNotNull();
    }

    private Long insertCampaign(String name, int totalStock, int remainingStock) {
        jdbcTemplate.update(
                "INSERT INTO campaign (name, total_stock, remaining_stock, status) VALUES (?, ?, ?, 'OPEN')",
                name, totalStock, remainingStock
        );
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Long insertUser(String loginId) {
        jdbcTemplate.update(
                "INSERT INTO users (login_id, name, phone, email, membership_tier) "
                        + "VALUES (?, ?, ?, ?, 'PRIVATE')",
                loginId, loginId, "010-0000-0000", loginId + "@test.local"
        );
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertIssuedCoupon(
            Long campaignId, Long userId, String couponCode, String idempotencyKey, LocalDateTime now
    ) {
        jdbcTemplate.update(
                "INSERT INTO coupon_issue "
                        + "(campaign_id, user_id, coupon_code, discount_type, discount_value, "
                        + " issued_membership_tier, status, idempotency_key, issued_at, valid_until) "
                        + "VALUES (?, ?, ?, 'RATE', 10.0000, 'PRIVATE', 'ISSUED', ?, ?, ?)",
                campaignId, userId, couponCode, idempotencyKey, now, now.plusHours(24)
        );
    }
}
