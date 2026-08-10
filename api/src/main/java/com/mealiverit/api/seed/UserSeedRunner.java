package com.mealiverit.api.seed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.datafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
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
