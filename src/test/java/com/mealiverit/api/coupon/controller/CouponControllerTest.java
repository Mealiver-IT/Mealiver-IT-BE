package com.mealiverit.api.coupon.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mealiverit.api.coupon.service.CouponIssueService;
import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import com.mealiverit.api.coupon.CouponStatus;
import com.mealiverit.api.coupon.DiscountType;
import com.mealiverit.api.coupon.entity.Coupon;
import com.mealiverit.api.coupon.entity.CouponIssue;
import com.mealiverit.api.coupon.entity.CouponStateLog;
import com.mealiverit.api.coupon.repository.CouponIssueRepository;
import com.mealiverit.api.coupon.repository.CouponRepository;
import com.mealiverit.api.coupon.repository.CouponStateLogRepository;
import com.mealiverit.api.user.MembershipTier;
import com.mealiverit.api.user.entity.User;
import com.mealiverit.api.user.repository.UserRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
public class CouponControllerTest {

    @Autowired
    private MockMvc mockMvc;
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
    @Autowired
    private CouponIssueService couponIssueService;

    @Test
    void 회수_성공시_200() throws Exception {
        Long issueId = createIssuedCoupon();

        mockMvc.perform(post("/api/admin/coupons/{issueId}/revoke", issueId)
                        .header("Idempotency-Key", "revoke-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        CouponIssue result = couponIssueRepository.findById(issueId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(CouponStatus.CANCELED);
    }

    @Test
    void 동일_idempotencyKey로_재요청해도_200이고_상태전이는_한번만_반영() throws Exception {
        Long issueId = createIssuedCoupon();
        String idempotencyKey = "revoke-" + UUID.randomUUID();

        mockMvc.perform(post("/api/admin/coupons/{issueId}/revoke", issueId)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/coupons/{issueId}/revoke", issueId)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        CouponIssue result = couponIssueRepository.findById(issueId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(CouponStatus.CANCELED);

        List<CouponStateLog> logs = couponStateLogRepository.findByCouponIssueIdOrderById(issueId);
        assertThat(logs).hasSize(1);
    }

    @Test
    void 존재하지_않는_쿠폰이면_404() throws Exception {
        mockMvc.perform(post("/api/admin/coupons/{issueId}/revoke", 999_999L)
                        .header("Idempotency-Key", "revoke-" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
    }

    @Test
    void idempotencyKey_헤더_누락시_400()  throws Exception {
        Long issueId = createIssuedCoupon();

        mockMvc.perform(post("/api/admin/coupons/{issueId}/revoke", issueId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 이미_사용된_쿠폰은_회수_불가_409() throws Exception {
        Long issuedId = createUsedCoupon();

        mockMvc.perform(post("/api/admin/coupons/{issueId}/revoke", issuedId)
                        .header("Idempotency-Key", "revoke-" + UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COUPON_INVALID_STATE_TRANSITION"));

        CouponIssue result = couponIssueRepository.findById(issuedId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(CouponStatus.USED); // 상태 안 바뀌었어야 함
    }

    private Long createIssuedCoupon() {
        Campaign campaign = campaignRepository.save(new Campaign("회수 테스트 캠페인", 10, null));
        Coupon coupon = couponRepository.save(new Coupon(campaign.getId(), DiscountType.FIXED, BigDecimal.valueOf(1000), null, null, 24));
        User user = userRepository.save(new User(
                "revoke-user" + System.nanoTime(), "테스트유저", "010-0000-0000",
                "revoke-" + System.nanoTime() + "@test.com"));
        CouponIssue issue = CouponIssue.issue(campaign.getId(), user.getId(),
                "idem-issue-" + UUID.randomUUID(), coupon, MembershipTier.PRIVATE, BigDecimal.valueOf(1000));

        return couponIssueRepository.save(issue).getId();
    }

    private Long createUsedCoupon() {
        Long issueId = createIssuedCoupon();
        couponIssueService.markUsed(issueId, "setup-used-" + UUID.randomUUID());
        return issueId;
    }
}
