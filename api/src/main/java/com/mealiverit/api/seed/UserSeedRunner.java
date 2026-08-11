package com.mealiverit.api.seed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.datafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// @Order(1): OrderSeedRunner(10)/MembershipTierSeedRunner(20)보다 먼저 실행돼야 함.
// @Order 없는 CommandLineRunner는 Ordered.LOWEST_PRECEDENCE로 취급돼 맨 뒤로 밀리므로,
// seed.enabled + seed.orders.enabled + seed.membershipTier.enabled를 한 번에 켰을 때
// users가 비어있는 채로 뒤 러너들이 먼저 도는 사고를 막기 위해 명시한다.
@Component
@Order(1)
@ConditionalOnProperty(name = "seed.enabled", havingValue = "true")
public class UserSeedRunner implements CommandLineRunner {

    private static final String INSERT_SQL =
        "INSERT INTO users (login_id, name, phone, email) VALUES (?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public UserSeedRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        Faker faker = new Faker();
        List<Object[]> batch = new ArrayList<>();
        StringBuilder json = new StringBuilder("[\n");

        for (int i = 0; i < 20_000; i++) {
            String loginId = "user" + i;
            batch.add(new Object[]{
                loginId,
                faker.name().fullName(),
                faker.phoneNumber().phoneNumber(),
                faker.internet().emailAddress()
            });

            json.append("  {\"userId\": \"").append(loginId)
                .append("\", \"idempotencyKey\": \"").append(UUID.randomUUID())
                .append("\"}");
            json.append(i < 19_999 ? ",\n" : "\n");
        }
        json.append("]\n");

        jdbcTemplate.batchUpdate(INSERT_SQL, batch);
        Files.writeString(Path.of("users_20000.json"), json.toString());

        System.out.println("done: 20000 users inserted + users_20000.json written");
    }
}
