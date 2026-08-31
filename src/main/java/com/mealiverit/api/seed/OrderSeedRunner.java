package com.mealiverit.api.seed;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 05_시스템설계.txt 2.2절: 목표 계급분포(이등병40/일병30/상병20/병장10)를 역산해
// 유저 그룹별로 orders 건수를 다르게 생성한다. users.membership_tier는 여기서 직접 채우지 않고
// (User.java 주석 참고) MembershipTierBatchJob이 이 orders를 집계해 매기도록 한다 — 로직 이원화 방지.
//
// ⚠️ 이 배치가 심는 completed_at은 SeedTargetMonth.resolve() 기준월(기본: 실행 시점 기준 전월) 안에 고정된다.
// MembershipTierBatchJob의 집계 윈도우([:월시작, :월종료), 05_시스템설계.txt 1.1 (f))도 같은
// SeedTargetMonth를 봐야 계급 분포가 맞으므로, MembershipTierSeedRunner가 이 값을 그대로 재사용한다
// (필요하면 -Dseed.orders.target-month=yyyy-MM 으로 두 러너 다 같은 값을 넘기면 된다).
//
// 재개(resume, 2026-08-25 추가): "이미 주문이 있는 유저는 건너뛴다"로는 안 된다 - 이등병 버킷은
// minOrders=0이라 주문 0건도 정상 결과이고, orders 테이블만 봐서는 "0건으로 끝난 유저"와
// "아직 처리 안 된 유저"를 구분할 수 없다(구분 못 하면 완주한 뒤 재실행해도 매번 이등병 구간
// 유저들을 다시 처리하게 됨 - 최초 구현에서 실제로 이 버그가 나서 고쳤다). 대신 유저를 id 오름차순
// 그대로 처리하는 걸 이용해 "지금까지 커밋된 주문 중 가장 큰 user_id"를 재개 기준점으로 쓴다 -
// 그 이하 id는 (0건으로 끝났든 아니든) 이미 처리된 구간으로 보고 건너뛴다. 버킷 배정은 유저
// 리스트상 위치(i)로만 정해지므로 skip 여부와 무관하게 원래 비율 그대로 유지된다.
@Component
@Order(10)
@ConditionalOnProperty(name = "seed.orders.enabled", havingValue = "true")
public class OrderSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(OrderSeedRunner.class);

    private static final String SELECT_USER_IDS_SQL = "SELECT id FROM users ORDER BY id";

    private static final String SELECT_MAX_PROCESSED_USER_ID_SQL = "SELECT MAX(user_id) FROM orders";

    private static final String INSERT_SQL =
        "INSERT INTO orders (user_id, order_amount, paid_amount, status, ordered_at, completed_at) "
            + "VALUES (?, ?, ?, 'COMPLETED', ?, ?)";

    private static final int BATCH_FLUSH_SIZE = 5_000;

    // 09_기획서.txt 6.2절 계급 구간: 이등병 0~2 / 일병 3~10 / 상병 11~30 / 병장 31~45(상한 임의)
    private static final TierBucket[] BUCKETS = {
        new TierBucket("이등병(PRIVATE)", 0.40, 0, 2),
        new TierBucket("일병(PFC)", 0.30, 3, 10),
        new TierBucket("상병(CORPORAL)", 0.20, 11, 30),
        new TierBucket("병장(SERGEANT)", 0.10, 31, 45),
    };

    private final JdbcTemplate jdbcTemplate;

    public OrderSeedRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        List<Long> userIds = jdbcTemplate.queryForList(SELECT_USER_IDS_SQL, Long.class);
        if (userIds.isEmpty()) {
            log.warn("skip: users 테이블이 비어 있음 (UserSeedRunner 먼저 실행할 것)");
            return;
        }

        Long maxProcessedObj = jdbcTemplate.queryForObject(SELECT_MAX_PROCESSED_USER_ID_SQL, Long.class);
        long resumeAfterUserId = maxProcessedObj == null ? 0L : maxProcessedObj;
        long highestUserId = userIds.get(userIds.size() - 1);

        if (resumeAfterUserId >= highestUserId) {
            log.info("skip: 이미 전체 유저(최대 id={}) 주문 시딩 완료 - 재개할 것 없음", highestUserId);
            return;
        }
        if (resumeAfterUserId > 0) {
            log.info("재개: user id <= {} 는 이미 처리된 구간으로 보고 건너뜀", resumeAfterUserId);
        }

        YearMonth targetMonth = SeedTargetMonth.resolve();
        LocalDateTime monthStart = targetMonth.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = targetMonth.atEndOfMonth().atTime(23, 59, 59);

        int[] cutoffs = computeCutoffs(userIds.size());

        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Object[]> batch = new ArrayList<>(BATCH_FLUSH_SIZE);
        long[] orderCountByBucket = new long[BUCKETS.length];
        long totalOrders = 0;

        int bucketIndex = 0;
        for (int i = 0; i < userIds.size(); i++) {
            while (bucketIndex < BUCKETS.length - 1 && i >= cutoffs[bucketIndex]) {
                bucketIndex++;
            }
            TierBucket bucket = BUCKETS[bucketIndex];
            long userId = userIds.get(i);

            if (userId <= resumeAfterUserId) {
                continue;
            }

            int orderCount = bucket.minOrders
                + (bucket.maxOrders > bucket.minOrders
                    ? random.nextInt(bucket.maxOrders - bucket.minOrders + 1)
                    : 0);
            orderCountByBucket[bucketIndex] += orderCount;
            totalOrders += orderCount;

            for (int o = 0; o < orderCount; o++) {
                LocalDateTime completedAt = randomInstantWithin(monthStart, monthEnd, random);
                LocalDateTime orderedAt = completedAt.minusHours(random.nextInt(1, 49));
                BigDecimal paidAmount = randomAmount(random, 10_000, 50_000);
                BigDecimal orderAmount = paidAmount.add(randomAmount(random, 0, 5_000));

                batch.add(new Object[]{userId, orderAmount, paidAmount, orderedAt, completedAt});
                if (batch.size() >= BATCH_FLUSH_SIZE) {
                    jdbcTemplate.batchUpdate(INSERT_SQL, batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_SQL, batch);
        }

        log.info("done: users={}, orders={}(이번 실행분), targetMonth={}, resumeAfterUserId={}",
            userIds.size(), totalOrders, targetMonth, resumeAfterUserId);
        for (int b = 0; b < BUCKETS.length; b++) {
            log.info("  {} -> orders={}", BUCKETS[b].label, orderCountByBucket[b]);
        }
    }

    // 유저 목록(id 오름차순)을 40/30/20/10 비율로 잘라 각 구간의 상한 인덱스(exclusive)를 반환.
    // 마지막 구간은 나머지 전부를 흡수해 반올림 오차로 인원이 누락되지 않게 한다.
    private int[] computeCutoffs(int totalUsers) {
        int[] cutoffs = new int[BUCKETS.length];
        double cumulative = 0;
        for (int i = 0; i < BUCKETS.length - 1; i++) {
            cumulative += BUCKETS[i].ratio;
            cutoffs[i] = (int) Math.round(totalUsers * cumulative);
        }
        cutoffs[BUCKETS.length - 1] = totalUsers;
        return cutoffs;
    }

    private LocalDateTime randomInstantWithin(LocalDateTime start, LocalDateTime end, ThreadLocalRandom random) {
        long startEpoch = start.toEpochSecond(java.time.ZoneOffset.UTC);
        long endEpoch = end.toEpochSecond(java.time.ZoneOffset.UTC);
        long randomEpoch = random.nextLong(startEpoch, endEpoch + 1);
        return LocalDateTime.ofEpochSecond(randomEpoch, 0, java.time.ZoneOffset.UTC);
    }

    private BigDecimal randomAmount(ThreadLocalRandom random, int minInclusive, int maxInclusive) {
        int step = 500;
        int steps = (maxInclusive - minInclusive) / step;
        int amount = minInclusive + (steps > 0 ? random.nextInt(steps + 1) * step : 0);
        return BigDecimal.valueOf(amount);
    }

    private static final class TierBucket {
        private final String label;
        private final double ratio;
        private final int minOrders;
        private final int maxOrders;

        private TierBucket(String label, double ratio, int minOrders, int maxOrders) {
            this.label = label;
            this.ratio = ratio;
            this.minOrders = minOrders;
            this.maxOrders = maxOrders;
        }
    }
}
