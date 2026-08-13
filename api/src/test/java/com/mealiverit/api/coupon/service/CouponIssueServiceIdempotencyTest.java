package com.mealiverit.api.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.entity.CouponStateLog;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponRepository;
import com.mealiverit.entity.coupon.repository.CouponStateLogRepository;
import com.mealiverit.entity.user.MembershipTier;
import com.mealiverit.entity.user.User;
import com.mealiverit.entity.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// FR-CPS-005: 동일 request_id로 상태변경 요청이 반복돼도 정확히 1회만 반영되어야 한다.
// 순차 재호출 시나리오라 Testcontainers 없이 H2로 충분 (실 락/데드락 검증이 목적이 아님)
@SpringBootTest
class CouponIssueServiceIdempotencyTest {

    @Autowired
    private CouponIssueService couponIssueService;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private CouponStateLogRepository couponStateLogRepository;

    @Test
    void 동일_requestId로_markUsed_두번_호출해도_한번만_반영() {
        Long issueId = createIssuedCoupon();
        String requestId = "idem-" + UUID.randomUUID();

        couponIssueService.markUsed(issueId, requestId);
        couponIssueService.markUsed(issueId, requestId); //재시도 상황을 흉내: 같은 requestId로 재호출

        CouponIssue result = couponIssueRepository.findById(issueId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(CouponStatus.USED);

        List<CouponStateLog> logs = couponStateLogRepository.findByCouponIssueIdOrderById(issueId);
        assertThat(logs).hasSize(1);
    }

    @Test
    void 동일_requestId로_markCanceledId_두번_호출해도_한번만_반영() {
        Long issueId = createIssuedCoupon();
        String requestId = "idem-" + UUID.randomUUID();

        couponIssueService.markCanceled(issueId, requestId);
        couponIssueService.markCanceled(issueId, requestId);

        CouponIssue result = couponIssueRepository.findById(issueId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(CouponStatus.CANCELED);

        List<CouponStateLog> logs = couponStateLogRepository.findByCouponIssueIdOrderById(issueId);
        assertThat(logs).hasSize(1);
    }

    @Test
    void 동일_requestId로_markReturnedToIssued_두번_호출해도_한번만_반영() {
        Long issueId = createUsedCoupon();
        String requestId = "idem-" + UUID.randomUUID();

        couponIssueService.markReturnedToIssued(issueId, requestId);
        couponIssueService.markReturnedToIssued(issueId, requestId);

        CouponIssue result = couponIssueRepository.findById(issueId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(result.getUsedAt()).isNull();

        //로그 2건: (설정용) ISSUED -> USED 1건, markReturnedToIssued로 인한 USED -> ISSUED 1건
        List<CouponStateLog> logs = couponStateLogRepository.findByCouponIssueIdOrderById(issueId);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(1).getFromStatus()).isEqualTo(CouponStatus.USED);
        assertThat(logs.get(1).getToStatus()).isEqualTo(CouponStatus.ISSUED);
    }

    private Long createIssuedCoupon() {
        Campaign campaign = campaignRepository.save(new Campaign("멱등성 테스트 캠페인", 10, null));
        Coupon coupon = couponRepository.save(new Coupon(campaign.getId(), DiscountType.FIXED, BigDecimal.valueOf(1000), null, null, 24));

        User user = userRepository.save(new User(
                "idem-user-" + System.nanoTime(), "테스트 유저", "010-0000-0000",
                "idem-" + System.nanoTime() + "@test.com"));

        CouponIssue issue = CouponIssue.issue(campaign.getId(), user.getId(),
                "idem-issue-" + UUID.randomUUID(), coupon, MembershipTier.PRIVATE, BigDecimal.valueOf(1000));

        return couponIssueRepository.save(issue).getId();
    }

    private Long createUsedCoupon() {
        Long issueId = createIssuedCoupon();
        couponIssueService.markUsed(issueId, "idem-" + UUID.randomUUID());
        return issueId;
    }
}
