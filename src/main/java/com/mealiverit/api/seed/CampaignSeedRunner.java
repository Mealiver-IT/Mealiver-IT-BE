package com.mealiverit.api.seed;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 05_시스템설계.txt 2.3절: 300만 발급이력(CouponIssueSeedRunner)을 만들기 전에 필요한
// 캠페인/쿠폰 마스터. 캠페인 15개, 총 재고 300만 — 등급 게이트(min_membership_tier)를
// 4단계 다 섞어서 eligibility 체크가 의미 있는 테스트 데이터가 되도록 구성했다.
// 캠페인:쿠폰은 1:1(Coupon.java 주석 참고).
//
// 재개(resume, 2026-08-25 추가): SPECS의 캠페인명은 전부 고정 문자열이라, 이미 존재하는 이름은
// 건너뛰고 없는 것만 채운다. campaign 테이블엔 이 러너 말고도 dirty-data 픽스처나 관리자 화면에서
// 만든 캠페인이 섞여 있을 수 있어 COUNT(*) 같은 단순 행수 비교 대신 이름으로 정확히 매칭한다.
@Component
@Order(30)
@ConditionalOnProperty(name = "seed.campaigns.enabled", havingValue = "true")
public class CampaignSeedRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CampaignSeedRunner.class);

    private static final String INSERT_CAMPAIGN_SQL =
        "INSERT INTO campaign (name, total_stock, remaining_stock, open_at, close_at, status, min_membership_tier) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_LAST_CAMPAIGN_ID_SQL =
        "SELECT id FROM campaign ORDER BY id DESC LIMIT 1";

    private static final String SELECT_EXISTING_NAMES_SQL = "SELECT name FROM campaign";

    private static final String INSERT_COUPON_SQL =
        "INSERT INTO coupon (campaign_id, discount_type, discount_value, min_order_amount, max_discount_amount, valid_hours) "
            + "VALUES (?, ?, ?, ?, ?, ?)";

    // name, totalStock, minTier(null=전체 오픈), discountType, discountValue, minOrderAmount, maxDiscountAmount, validHours
    private static final List<CampaignSpec> SPECS = List.of(
        new CampaignSpec("전체 오픈런 이벤트", 400_000, null, "RATE", "0.10", "10000", "5000", 24),
        new CampaignSpec("신규가입 웰컴쿠폰", 300_000, null, "FIXED", "3000", "10000", null, 72),
        new CampaignSpec("주말 특가", 250_000, null, "RATE", "0.10", "15000", "5000", 48),
        new CampaignSpec("심야 할인", 150_000, null, "FIXED", "2000", null, null, 12),
        new CampaignSpec("이등병 환영 쿠폰", 200_000, "PRIVATE", "FIXED", "2000", "10000", null, 168),
        new CampaignSpec("일병 진급 기념", 200_000, "PFC", "RATE", "0.10", "10000", "5000", 72),
        new CampaignSpec("일병 전용 특가", 150_000, "PFC", "FIXED", "3000", "10000", null, 48),
        new CampaignSpec("상병 VIP 쿠폰", 150_000, "CORPORAL", "RATE", "0.30", "10000", "10000", 72),
        new CampaignSpec("상병 감사 이벤트", 100_000, "CORPORAL", "FIXED", "5000", "10000", null, 48),
        new CampaignSpec("상병 주간 특가", 80_000, "CORPORAL", "RATE", "0.30", "15000", "8000", 24),
        new CampaignSpec("병장 프리미엄", 60_000, "SERGEANT", "RATE", "0.50", "10000", "20000", 168),
        new CampaignSpec("병장 전역 기념", 40_000, "SERGEANT", "FIXED", "10000", "10000", null, 72),
        new CampaignSpec("병장 단독 초대", 30_000, "SERGEANT", "RATE", "0.50", "20000", "15000", 24),
        new CampaignSpec("깜짝 타임세일", 500_000, null, "FIXED", "2000", null, null, 6),
        new CampaignSpec("연말 대박 이벤트", 390_000, null, "RATE", "0.10", "10000", "5000", 24)
    );

    private final JdbcTemplate jdbcTemplate;

    public CampaignSeedRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // @Transactional: campaign INSERT 직후 SELECT LAST_INSERT_ID()로 방금 만든 id를 가져오는데,
    // 커넥션 풀에서 두 호출이 서로 다른 커넥션을 잡으면 LAST_INSERT_ID()가 엉뚱한 값(0 등)을 반환한다.
    // 트랜잭션으로 묶어야 이 run() 안의 jdbcTemplate 호출들이 같은 커넥션을 계속 재사용한다.
    @Override
    @Transactional
    public void run(String... args) {
        Set<String> existingNames = new HashSet<>(jdbcTemplate.queryForList(SELECT_EXISTING_NAMES_SQL, String.class));

        LocalDateTime openAt = LocalDateTime.now().minusDays(30);
        LocalDateTime closeAt = openAt.plusDays(90);
        long totalStock = 0;
        int inserted = 0;
        int skipped = 0;

        for (CampaignSpec spec : SPECS) {
            if (existingNames.contains(spec.name)) {
                skipped++;
                continue;
            }

            jdbcTemplate.update(INSERT_CAMPAIGN_SQL,
                spec.name, spec.totalStock, spec.totalStock, openAt, closeAt, "OPEN", spec.minTier);
            Long campaignId = jdbcTemplate.queryForObject(SELECT_LAST_CAMPAIGN_ID_SQL, Long.class);

            jdbcTemplate.update(INSERT_COUPON_SQL,
                campaignId, spec.discountType, new BigDecimal(spec.discountValue),
                spec.minOrderAmount == null ? null : new BigDecimal(spec.minOrderAmount),
                spec.maxDiscountAmount == null ? null : new BigDecimal(spec.maxDiscountAmount),
                spec.validHours);

            totalStock += spec.totalStock;
            inserted++;
        }

        log.info("done: {} campaigns + coupons inserted (skip {} already existing), totalStock={}",
            inserted, skipped, totalStock);
    }

    private static final class CampaignSpec {
        private final String name;
        private final int totalStock;
        private final String minTier;
        private final String discountType;
        private final String discountValue;
        private final String minOrderAmount;
        private final String maxDiscountAmount;
        private final int validHours;

        private CampaignSpec(String name, int totalStock, String minTier, String discountType,
                              String discountValue, String minOrderAmount, String maxDiscountAmount,
                              int validHours) {
            this.name = name;
            this.totalStock = totalStock;
            this.minTier = minTier;
            this.discountType = discountType;
            this.discountValue = discountValue;
            this.minOrderAmount = minOrderAmount;
            this.maxDiscountAmount = maxDiscountAmount;
            this.validHours = validHours;
        }
    }
}
