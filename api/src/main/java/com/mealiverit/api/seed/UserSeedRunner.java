package com.mealiverit.api.seed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// @Order(1): OrderSeedRunner(10)/MembershipTierSeedRunner(20)보다 먼저 실행돼야 함.
// @Order 없는 CommandLineRunner는 Ordered.LOWEST_PRECEDENCE로 취급돼 맨 뒤로 밀리므로,
// seed.enabled + seed.orders.enabled + seed.membershipTier.enabled를 한 번에 켰을 때
// users가 비어있는 채로 뒤 러너들이 먼저 도는 사고를 막기 위해 명시한다.
//
// seed.userCount로 유저 수를 조절한다(기본 20,000 — 기존 부하테스트 리허설용 동작과 동일).
// Phase1의 100만 User 시딩은 --seed.enabled=true --seed.userCount=1000000 처럼 넘기면 됨.
// 5,000건 단위로 청크 flush(OrderSeedRunner와 동일 패턴) — 100만 건을 한 번에 메모리에 안 쌓는다.
// 부하테스트용 users_<n>.json(userId+idempotencyKey 목록)은 리허설 규모(5만 명 이하)일 때만 생성한다 —
// 100만 규모에선 k6 VU 식별자라는 원래 용도에 안 맞고 파일만 쓸데없이 커진다.
@Component
@Order(1)
@ConditionalOnProperty(name = "seed.enabled", havingValue = "true")
public class UserSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeedRunner.class);

    private static final String INSERT_SQL =
        "INSERT INTO users (login_id, name, phone, email) VALUES (?, ?, ?, ?)";

    private static final int BATCH_FLUSH_SIZE = 5_000;
    private static final int LOAD_TEST_JSON_THRESHOLD = 50_000;

    private final JdbcTemplate jdbcTemplate;
    private final int userCount;

    public UserSeedRunner(JdbcTemplate jdbcTemplate, @Value("${seed.userCount:20000}") int userCount) {
        this.jdbcTemplate = jdbcTemplate;
        this.userCount = userCount;
    }

    @Override
    public void run(String... args) throws Exception {
        Faker faker = new Faker();
        List<Object[]> batch = new ArrayList<>(BATCH_FLUSH_SIZE);
        boolean writeLoadTestJson = userCount <= LOAD_TEST_JSON_THRESHOLD;
        StringBuilder json = writeLoadTestJson ? new StringBuilder("[\n") : null;

        for (int i = 0; i < userCount; i++) {
            String loginId = "user" + i;
            batch.add(new Object[]{
                loginId,
                faker.name().fullName(),
                faker.phoneNumber().phoneNumber(),
                faker.internet().emailAddress()
            });

            if (writeLoadTestJson) {
                json.append("  {\"userId\": \"").append(loginId)
                    .append("\", \"idempotencyKey\": \"").append(UUID.randomUUID())
                    .append("\"}");
                json.append(i < userCount - 1 ? ",\n" : "\n");
            }

            if (batch.size() >= BATCH_FLUSH_SIZE) {
                jdbcTemplate.batchUpdate(INSERT_SQL, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_SQL, batch);
        }

        if (writeLoadTestJson) {
            json.append("]\n");
            String fileName = "users_" + userCount + ".json";
            Files.writeString(Path.of(fileName), json.toString());
            log.info("done: {} users inserted + {} written", userCount, fileName);
        } else {
            log.info("done: {} users inserted (load-test json skipped, count > {})",
                userCount, LOAD_TEST_JSON_THRESHOLD);
        }
    }
}
