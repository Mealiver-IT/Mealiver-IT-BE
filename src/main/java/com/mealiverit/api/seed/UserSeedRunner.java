package com.mealiverit.api.seed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
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
//
// 재개(resume, 2026-08-25 추가): login_id가 항상 "user0"부터 순서대로 빈틈없이 들어가므로
// (users 테이블은 이 러너만 채움 - 다른 러너/실사용자 가입 API 없음), 이미 존재하는 행 수를
// 그대로 다음 시작 인덱스로 쓸 수 있다. Tailscale 등 네트워크가 불안정한 환경에서 100만 규모
// 시딩 도중 죽어도, 재실행하면 uk_users_login_id 위반 없이 이어서 채운다
// (CouponIssueSeedRunner의 재개 로직과 같은 이유 - #34 PR 참고).
@Component
@Order(1)
@ConditionalOnProperty(name = "seed.enabled", havingValue = "true")
public class UserSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeedRunner.class);

    private static final String INSERT_SQL =
        "INSERT INTO users (login_id, name, phone, email) VALUES (?, ?, ?, ?)";

    private static final String COUNT_EXISTING_SQL = "SELECT COUNT(*) FROM users";

    private static final String SELECT_EXISTING_PHONES_SQL = "SELECT phone FROM users";

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
        int startIndex = resolveStartIndex();
        if (startIndex >= userCount) {
            log.info("skip: 이미 {}명 존재 (목표 {}명) - 재개할 것 없음", startIndex, userCount);
            return;
        }
        if (startIndex > 0) {
            log.info("재개: user{}부터 이어서 시작 (이미 {}명 존재)", startIndex, startIndex);
        }

        Faker faker = new Faker();
        Random random = new Random();
        List<Object[]> batch = new ArrayList<>(BATCH_FLUSH_SIZE);
        // 재개 시 이미 있는 유저들의 phone과도 겹치면 안 되므로 기존 값을 먼저 채워둔다.
        Set<String> usedPhones = new HashSet<>(jdbcTemplate.queryForList(SELECT_EXISTING_PHONES_SQL, String.class));

        for (int i = startIndex; i < userCount; i++) {
            batch.add(new Object[]{
                "user" + i,
                KoreanDummyDataGenerator.randomName(random),
                KoreanDummyDataGenerator.randomPhone(random, usedPhones),
                faker.internet().emailAddress()
            });

            if (batch.size() >= BATCH_FLUSH_SIZE) {
                jdbcTemplate.batchUpdate(INSERT_SQL, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_SQL, batch);
        }

        // k6 VU 목록(users_<n>.json)은 DB insert와 무관하게 항상 user0..userCount-1 전체를 담아야
        // 쓸모가 있으므로, 재개 여부와 상관없이(startIndex와 별개로) 항상 전체 범위로 새로 만든다.
        if (userCount <= LOAD_TEST_JSON_THRESHOLD) {
            String fileName = writeLoadTestJson();
            log.info("done: {} users inserted (이번 실행분 {}명, user{}~user{}) + {} written",
                userCount, userCount - startIndex, startIndex, userCount - 1, fileName);
        } else {
            log.info("done: {} users inserted (이번 실행분 {}명, load-test json skipped, count > {})",
                userCount, userCount - startIndex, LOAD_TEST_JSON_THRESHOLD);
        }
    }

    private int resolveStartIndex() {
        Integer existing = jdbcTemplate.queryForObject(COUNT_EXISTING_SQL, Integer.class);
        return existing == null ? 0 : existing;
    }

    private String writeLoadTestJson() throws Exception {
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < userCount; i++) {
            json.append("  {\"userId\": \"user").append(i)
                .append("\", \"idempotencyKey\": \"").append(UUID.randomUUID())
                .append("\"}");
            json.append(i < userCount - 1 ? ",\n" : "\n");
        }
        json.append("]\n");
        String fileName = "users_" + userCount + ".json";
        Files.writeString(Path.of(fileName), json.toString());
        return fileName;
    }
}
