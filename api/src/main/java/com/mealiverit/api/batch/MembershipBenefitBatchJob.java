package com.mealiverit.api.batch;

import com.mealiverit.api.batch.MembershipBenefitPolicy.BenefitCoupon;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.campaign.CampaignType;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.repository.CouponRepository;
import com.mealiverit.entity.user.MembershipTier;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 계급별 월간 혜택 쿠폰 지급.
// MembershipTierBatchJob.runMonthly()가 계급 재산정 직후 이 클래스를 직접 호출
// "새로 계산된 계급" 기준으로 혜택이 나가야 하므로 호출 순서가 중요
// uk_campaign_user(campaign_id, user_id) 제약상 캠페인 1개당 유저 1인 1장이라,
// "N장 지급"은 등급×슬롯 단위로 캠페인을 N개 만드는 방식으로 구현함
@Component
public class MembershipBenefitBatchJob {
    private static final Logger LOG = LoggerFactory.getLogger(MembershipBenefitBatchJob.class);
    private static final DateTimeFormatter MONTH_FORMAT =  DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int VALID_HOURS = 24 * 30; // 발급 시점부터 30일

    private static final String SELECT_USER_BY_TIER_SQL = "SELECT id FROM users WHERE membership_tier = ? ORDER BY id";
    private static final String SELECT_ISSUE_USER_IDS_SQL = "SELECT user_id FROM coupon_issue WHERE campaign_id = ?";
    private static final String INSERT_ISSUE_SQL = "INSERT INTO coupon_issue (campaign_id, user_id, coupon_code, discount_type, " +
            "discount_value, max_discount_amount, issued_membership_tier, status, idempotency_key, issued_at, valid_until) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?, ?)";

    private static final int BATCH_FLUSH_SIZE = 5000;

    private final CampaignRepository campaignRepository;
    private final CouponRepository couponRepository;
    private final JdbcTemplate jdbcTemplate;

    public MembershipBenefitBatchJob(CampaignRepository campaignRepository, CouponRepository couponRepository, JdbcTemplate jdbcTemplate) {
        this.campaignRepository = campaignRepository;
        this.couponRepository = couponRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    // targetMonth: 이 혜택이 적용되는 "이번 달"
    // MembershipTierBatchJob의 targetMonth와는 다른 값 - 호출부에서 YearMonth.now()를 넘김
    public void run(YearMonth targetMonth) {
        for (MembershipTier tier : MembershipTier.values()) {
            List<MembershipBenefitPolicy.BenefitCoupon> benefits = MembershipBenefitPolicy.couponsFor(tier);
            if (benefits.isEmpty()) {
                continue;
            }
            List<Long> eligibleUserIds = jdbcTemplate.queryForList(SELECT_USER_BY_TIER_SQL, Long.class, tier.name());
            for (int slot = 1; slot <= benefits.size(); slot++) {
                issueSlot(targetMonth, tier, slot, benefits.get(slot-1), eligibleUserIds);
            }
        }
    }

    // 합성 캠페인 1개(등급+슬롯 단위) 발급 처리
    // 이름에 월/등급/슬롯을 그대로 노출해 디버깅하기 쉽게 함
    // TODO(하드닝): issueSlot() 전체가 @Transactional이 아님 - 캠페인/쿠폰 생성, coupon_issue 벌크 삽입, markFullyIssued() 저장이 각각 별도 트랜잭션으로 커밋된다.
    //  중간에 실패하면 "발급은 됐는데 remaining_stock은 그대로"인 상태가 남을 수 있음 (PR 리뷰 코멘트, 2026-08-19)
    private void issueSlot(YearMonth targetMonth, MembershipTier tier, int slot, BenefitCoupon benefit, List<Long> eligibleUserIds) {
        String campaignName = "MEMBERSHIP_BENEFIT_%s_%s_%d".formatted(targetMonth.format(MONTH_FORMAT), tier.name(), slot);

        // TODO(하드닝): 캠페인 재사용 시 total_stock을 갱신하지 않음
        //  같은 달에 이 배치가 두 번 실행되고 그 사이 대상 유저가 늘면(POST /api/admin/membership/refresh 수동 트리거로 실제 발생 가능)
        //  실발급 건수가 total_stock을 넘어 검증쿼리(a)가 초과발급으로 오탐할 수 있음(PR 리뷰 코멘트, 2026-08-19)
        Campaign campaign = campaignRepository.findByName(campaignName)
                .orElseGet(() -> campaignRepository.save(new Campaign(campaignName, eligibleUserIds.size(), tier, CampaignType.MEMBERSHIP_BENEFIT)));
        Coupon coupon = couponRepository.findByCampaignId(campaign.getId())
                .orElseGet(() -> couponRepository.save(new Coupon(campaign.getId(), benefit.discountType(), benefit.discountValue(), null, null, VALID_HOURS)));

        Set<Long> alreadyIssued = new HashSet<>(jdbcTemplate.queryForList(SELECT_ISSUE_USER_IDS_SQL, Long.class, campaign.getId()));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime validUntil = now.plusHours(coupon.getValidHours());
        List<Object[]> batch = new ArrayList<>(BATCH_FLUSH_SIZE);
        int issuedCount = 0;

        for (Long userId : eligibleUserIds) {
            if (alreadyIssued.contains(userId)) {
                continue;
            }
            batch.add(new Object[]{
                    campaign.getId(), userId, "CPN-" + UUID.randomUUID(), coupon.getDiscountType().name(), coupon.getDiscountValue(),
                    coupon.getMaxDiscountAmount(), tier.name(), "membership-benefit-" + campaign.getId() + "-" +userId, now, validUntil
            });
            issuedCount++;
            if (batch.size() >= BATCH_FLUSH_SIZE) {
                jdbcTemplate.batchUpdate(INSERT_ISSUE_SQL, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_ISSUE_SQL, batch);
        }

        campaign.markFullyIssued();
        campaignRepository.save(campaign);

        LOG.info("MembershipBenefitBatchJob campaign[{}]({}) issued(this run)={}, eligible={}",
                campaign.getId(), campaignName, issuedCount, eligibleUserIds.size());
    }
}
