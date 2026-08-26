package com.mealiverit.api.seed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 일회성 백필: 이미 시딩되어 있는(예: Tailscale 원격 DB) users의 name/phone만 KoreanDummyDataGenerator로
// 교체한다. UserSeedRunner는 새 유저를 만들 때만 관여하므로, 이미 완주된 DB(재개 로직이 "이미 목표치만큼
// 있음"으로 skip함)에는 기존 미국식 데이터를 새로 넣을 방법이 없어서 별도로 만들었다.
// id/login_id/email/membership_tier 등 다른 컬럼과 orders/coupon_issue 등 참조 관계는 전혀 안 건드림.
//
// UPDATE는 rewriteBatchedStatements=true를 써도 여러 행을 한 문장으로 못 묶는다(MembershipTierBatchJob
// 주석 참고, 100만 건 기준 행 단위 UPDATE는 실측 3시간+ 소요했던 전례가 있음) - 그래서 여기도 동일하게
// CASE WHEN으로 청크(1,000건)당 한 문장만 날린다.
@Component
@ConditionalOnProperty(name = "seed.updateKoreanUserData.enabled", havingValue = "true")
public class UpdateKoreanUserDataRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UpdateKoreanUserDataRunner.class);

    private static final String SELECT_IDS_SQL = "SELECT id FROM users ORDER BY id";

    private static final int CHUNK_SIZE = 1_000;

    private final JdbcTemplate jdbcTemplate;

    public UpdateKoreanUserDataRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        List<Long> ids = jdbcTemplate.queryForList(SELECT_IDS_SQL, Long.class);
        if (ids.isEmpty()) {
            log.warn("skip: users 테이블이 비어 있음");
            return;
        }

        Random random = new Random();
        List<Object[]> chunk = new ArrayList<>(CHUNK_SIZE);
        // 전원 재생성이라 기존 값은 어차피 다 덮어써짐 - 이번 실행에서 새로 뽑은 값끼리만 겹치지 않으면 됨.
        Set<String> usedPhones = new HashSet<>();
        long updated = 0;

        for (long id : ids) {
            chunk.add(new Object[]{id, KoreanDummyDataGenerator.randomName(random), KoreanDummyDataGenerator.randomPhone(random, usedPhones)});
            if (chunk.size() >= CHUNK_SIZE) {
                flushUpdate(chunk);
                updated += chunk.size();
                chunk.clear();
                if (updated % 100_000 == 0) {
                    log.info("progress: {}/{}", updated, ids.size());
                }
            }
        }
        if (!chunk.isEmpty()) {
            flushUpdate(chunk);
            updated += chunk.size();
        }

        log.info("done: {} users updated with Korean name/phone", updated);
    }

    // (id, name, phone) 청크를 CASE WHEN 두 개(name/phone)로 묶은 UPDATE 1문장으로 실행.
    private void flushUpdate(List<Object[]> chunk) {
        StringBuilder sql = new StringBuilder("UPDATE users SET name = CASE id ");
        for (int i = 0; i < chunk.size(); i++) {
            sql.append("WHEN ? THEN ? ");
        }
        sql.append("END, phone = CASE id ");
        for (int i = 0; i < chunk.size(); i++) {
            sql.append("WHEN ? THEN ? ");
        }
        sql.append("END WHERE id IN (");
        for (int i = 0; i < chunk.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(")");

        Object[] params = new Object[chunk.size() * 5]; // name(id,val) + phone(id,val) + WHERE IN(id)
        int idx = 0;
        for (Object[] row : chunk) {
            params[idx++] = row[0];
            params[idx++] = row[1]; // name
        }
        for (Object[] row : chunk) {
            params[idx++] = row[0];
            params[idx++] = row[2]; // phone
        }
        for (Object[] row : chunk) {
            params[idx++] = row[0];
        }

        jdbcTemplate.update(sql.toString(), params);
    }
}
