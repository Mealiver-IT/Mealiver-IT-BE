package com.mealiverit.api.seed;

import com.mealiverit.entity.user.MembershipTier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

// 05_시스템설계.txt 2.3절: 300만 발급이력(정상 케이스 위주). 오염 데이터(초과발급/중복발급/등급위반 등)는
// Phase2에서 별도로 심는다 — 여기서는 uk_campaign_user/idempotency_key/coupon_code 유니크 제약을
// 전부 지키고, campaign.min_membership_tier eligibility도 실제로 만족하는 유저에게만 발급한다.
//
// - campaign마다 eligibility(등급 게이트)를 만족하는 유저 풀에서 total_stock만큼(또는 일부 캠페인은
//   "다양한 발급량" 요구사항에 맞춰 60~90%만) 중복 없이 뽑아 발급한다.
// - issued_membership_tier는 발급 시점 스냅샷 — 실제 users.membership_tier 값을 그대로 찍는다
//   (CouponIssue.issue() 정적 팩토리와 동일한 의미).
// - discount_value: RATE 타입이면 TierDiscountPolicy(04_아키텍처.txt 6.1절, 이등병/일병 10%·상병 30%·
//   병장 50%)를 그대로 미러링해 유저 등급 기준으로 재계산, FIXED면 coupon 기본값 그대로.
// - 발급 후 campaign.remaining_stock/status를 실제 발급 결과에 맞게 갱신한다.
@Component
@Order(40)
@ConditionalOnProperty(name = "seed.couponIssues.enabled", havingValue = "true")
public class CouponIssueSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CouponIssueSeedRunner.class);

    private static final String SELECT_USERS_SQL = "SELECT id, membership_tier FROM users ORDER BY id";

    private static final String SELECT_CAMPAIGNS_SQL =
        "SELECT id, total_stock, min_membership_tier FROM campaign ORDER BY id";

    private static final String SELECT_COUPON_SQL =
        "SELECT id, discount_type, discount_value, max_discount_amount, valid_hours FROM coupon WHERE campaign_id = ?";

    private static final String COUNT_EXISTING_ISSUES_SQL =
        "SELECT COUNT(*) FROM coupon_issue WHERE campaign_id = ?";

    private static final String SELECT_ISSUED_USER_IDS_SQL =
        "SELECT user_id FROM coupon_issue WHERE campaign_id = ?";

    private static final String INSERT_ISSUE_SQL =
        "INSERT INTO coupon_issue (campaign_id, user_id, coupon_code, discount_type, discount_value, "
            + "max_discount_amount, issued_membership_tier, status, idempotency_key, issued_at, valid_until) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?, ?)";

    private static final String UPDATE_CAMPAIGN_SQL =
        "UPDATE campaign SET remaining_stock = ?, status = ? WHERE id = ?";

    private static final int BATCH_FLUSH_SIZE = 5_000;

    // 04_아키텍처.txt 6.1절 TierDiscountPolicy와 동일 매핑 — 아직 애플리케이션 코드로 구현되기 전이라
    // 시더에서 같은 규칙을 그대로 미러링한다. 실제 서비스 코드가 생기면 이쪽도 그걸 가져다 쓰도록 바꿀 것.
    private static final Map<MembershipTier, BigDecimal> TIER_RATE = Map.of(
        MembershipTier.PRIVATE, new BigDecimal("0.10"),
        MembershipTier.PFC, new BigDecimal("0.10"),
        MembershipTier.CORPORAL, new BigDecimal("0.30"),
        MembershipTier.SERGEANT, new BigDecimal("0.50")
    );

    private final JdbcTemplate jdbcTemplate;

    public CouponIssueSeedRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        List<UserRow> users = jdbcTemplate.query(SELECT_USERS_SQL, userRowMapper());
        if (users.isEmpty()) {
            log.warn("skip: users 테이블이 비어 있음");
            return;
        }
        List<CampaignRow> campaigns = jdbcTemplate.query(SELECT_CAMPAIGNS_SQL, campaignRowMapper());
        if (campaigns.isEmpty()) {
            log.warn("skip: campaign 테이블이 비어 있음 (CampaignSeedRunner 먼저 실행할 것)");
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        LocalDateTime windowEnd = LocalDateTime.now();
        LocalDateTime windowStart = windowEnd.minusDays(30);

        List<Object[]> batch = new ArrayList<>(BATCH_FLUSH_SIZE);
        long totalIssued = 0;

        for (int c = 0; c < campaigns.size(); c++) {
            CampaignRow campaign = campaigns.get(c);

            // 캠페인 4개 중 1개꼴로 일부러 재고를 남겨 "완판 vs 진행중"이 섞이게 한다
            // (05_시스템설계.txt 2.3절 "캠페인별 재고/발급량을 다양하게").
            boolean partial = c % 4 == 3;

            // 중간에 프로세스가 죽었다가 재실행되는 경우를 대비한 재개(resume) 로직.
            // partial 캠페인은 목표치가 매 실행마다 랜덤이라 정확히 복구할 수 없으니, 발급이력이
            // 하나라도 있으면 이미 의도한 대로 처리된 것으로 보고 건너뛴다.
            // full 캠페인은 목표(total_stock)가 고정이라, 죽기 전까지 발급된 수가 모자라면
            // 남은 만큼만 이어서 채운다 (이미 발급받은 유저는 uk_campaign_user 위반 방지를 위해 제외).
            Integer existingObj = jdbcTemplate.queryForObject(COUNT_EXISTING_ISSUES_SQL, Integer.class, campaign.id);
            int existing = existingObj == null ? 0 : existingObj;
            if (partial && existing > 0) {
                log.info("campaign[{}] skip (partial, 이미 발급이력 {}건 존재)", campaign.id, existing);
                continue;
            }
            if (!partial && existing >= campaign.totalStock) {
                log.info("campaign[{}] skip (이미 완판, {}건)", campaign.id, existing);
                continue;
            }

            CouponRow coupon = jdbcTemplate.queryForObject(SELECT_COUPON_SQL, couponRowMapper(), campaign.id);

            Set<Long> alreadyIssuedUserIds = existing > 0
                ? new HashSet<>(jdbcTemplate.queryForList(SELECT_ISSUED_USER_IDS_SQL, Long.class, campaign.id))
                : Set.of();

            List<UserRow> eligible = users.stream()
                .filter(u -> campaign.minTier == null || u.tier.ordinal() >= campaign.minTier.ordinal())
                .filter(u -> !alreadyIssuedUserIds.contains(u.id))
                .collect(Collectors.toCollection(ArrayList::new));
            Collections.shuffle(eligible, new Random(random.nextLong()));

            int target = partial
                ? (int) (campaign.totalStock * (0.6 + random.nextDouble() * 0.3))
                : campaign.totalStock;
            int issueCount = Math.max(0, target - existing);
            issueCount = Math.min(issueCount, eligible.size());

            for (int i = 0; i < issueCount; i++) {
                UserRow user = eligible.get(i);
                LocalDateTime issuedAt = randomInstantWithin(windowStart, windowEnd, random);
                LocalDateTime validUntil = issuedAt.plusHours(coupon.validHours);
                BigDecimal discountValue = "RATE".equals(coupon.discountType)
                    ? TIER_RATE.get(user.tier)
                    : coupon.discountValue;

                batch.add(new Object[]{
                    campaign.id, user.id, "CPN-" + fastRandomUuid(random),
                    coupon.discountType, discountValue, coupon.maxDiscountAmount,
                    user.tier.name(), fastRandomUuid(random).toString(), issuedAt, validUntil
                });
                totalIssued++;

                if (batch.size() >= BATCH_FLUSH_SIZE) {
                    jdbcTemplate.batchUpdate(INSERT_ISSUE_SQL, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(INSERT_ISSUE_SQL, batch);
                batch.clear();
            }

            int totalAfterThisRun = existing + issueCount;
            int remaining = Math.max(0, campaign.totalStock - totalAfterThisRun);
            String status = remaining == 0 ? "CLOSED" : "OPEN";
            jdbcTemplate.update(UPDATE_CAMPAIGN_SQL, remaining, status, campaign.id);

            log.info("campaign[{}] {} -> issued(this run)={}, total={}/{}", campaign.id,
                partial ? "partial" : "full", issueCount, totalAfterThisRun, campaign.totalStock);
        }

        log.info("done: campaigns={}, totalIssued={}", campaigns.size(), totalIssued);
    }

    // UUID.randomUUID()는 내부적으로 SecureRandom을 쓰는데, 이게 (특히 Windows에서) 눈에 띄게 느리다.
    // 시드 데이터는 유일성만 있으면 되고 암호학적 안전성이 필요 없으므로, ThreadLocalRandom으로 128비트를
    // 직접 채워서 UUID를 만든다 — 형식은 동일(문자열로 찍으면 정상 UUID 모양), 속도만 훨씬 빠르다.
    private java.util.UUID fastRandomUuid(ThreadLocalRandom random) {
        return new java.util.UUID(random.nextLong(), random.nextLong());
    }

    private LocalDateTime randomInstantWithin(LocalDateTime start, LocalDateTime end, ThreadLocalRandom random) {
        long startEpoch = start.toEpochSecond(java.time.ZoneOffset.UTC);
        long endEpoch = end.toEpochSecond(java.time.ZoneOffset.UTC);
        long randomEpoch = random.nextLong(startEpoch, endEpoch + 1);
        return LocalDateTime.ofEpochSecond(randomEpoch, 0, java.time.ZoneOffset.UTC);
    }

    private RowMapper<UserRow> userRowMapper() {
        return (rs, rowNum) -> new UserRow(rs.getLong("id"), MembershipTier.valueOf(rs.getString("membership_tier")));
    }

    private RowMapper<CampaignRow> campaignRowMapper() {
        return (rs, rowNum) -> {
            String tierStr = rs.getString("min_membership_tier");
            MembershipTier tier = tierStr == null ? null : MembershipTier.valueOf(tierStr);
            return new CampaignRow(rs.getLong("id"), rs.getInt("total_stock"), tier);
        };
    }

    private RowMapper<CouponRow> couponRowMapper() {
        return (rs, rowNum) -> new CouponRow(
            rs.getString("discount_type"),
            rs.getBigDecimal("discount_value"),
            rs.getBigDecimal("max_discount_amount"),
            rs.getInt("valid_hours")
        );
    }

    private static final class UserRow {
        private final long id;
        private final MembershipTier tier;

        private UserRow(long id, MembershipTier tier) {
            this.id = id;
            this.tier = tier;
        }
    }

    private static final class CampaignRow {
        private final long id;
        private final int totalStock;
        private final MembershipTier minTier;

        private CampaignRow(long id, int totalStock, MembershipTier minTier) {
            this.id = id;
            this.totalStock = totalStock;
            this.minTier = minTier;
        }
    }

    private static final class CouponRow {
        private final String discountType;
        private final BigDecimal discountValue;
        private final BigDecimal maxDiscountAmount;
        private final int validHours;

        private CouponRow(String discountType, BigDecimal discountValue, BigDecimal maxDiscountAmount,
                           int validHours) {
            this.discountType = discountType;
            this.discountValue = discountValue;
            this.maxDiscountAmount = maxDiscountAmount;
            this.validHours = validHours;
        }
    }
}
