package com.mealiverit.api.coupon.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import com.mealiverit.api.coupon.DiscountType;
import com.mealiverit.api.coupon.entity.Coupon;
import com.mealiverit.api.coupon.repository.CouponRepository;
import com.mealiverit.api.user.MembershipTier;
import com.mealiverit.api.user.entity.User;
import com.mealiverit.api.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CouponClaimControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    void 발급_성공시_201과_쿠폰정보_반환() throws Exception {
        Long campaignId = createCampaign(10, null);
        Long userId = createUser(MembershipTier.PRIVATE);

        mockMvc.perform(post("/api/campaigns/{campaignId}/coupons", campaignId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "idem-" + userId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.discountType").value("FIXED"));
    }

    @Test
    void 동일_idempotencyKey_재요청시_200과_같은_쿠폰_반환() throws Exception {
        Long campaignId = createCampaign(10, null);
        Long userId = createUser(MembershipTier.PRIVATE);
        String idempotencyKey = "idem-" + userId;

        String firstBody = mockMvc.perform(post("/api/campaigns/{campaignId}/coupons", campaignId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/campaigns/{campaignId}/coupons", campaignId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.couponCode")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyOrNullString())));

        org.hamcrest.MatcherAssert.assertThat(firstBody, org.hamcrest.Matchers.containsString("ISSUED"));
    }

    @Test
    void 재고_소진시_409() throws Exception {
        Long campaignId = createCampaign(0, null);
        Long userId = createUser(MembershipTier.PRIVATE);

        mockMvc.perform(post("/api/campaigns/{campaignId}/coupons", campaignId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "idem-" + userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));
    }

    @Test
    void 등급_미달시_403() throws Exception {
        Long campaignId = createCampaign(10, MembershipTier.SERGEANT);
        Long userId = createUser(MembershipTier.PRIVATE);

        mockMvc.perform(post("/api/campaigns/{campaignId}/coupons", campaignId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "idem-" + userId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_TIER_NOT_ELIGIBLE"));
    }

    @Test
    void idempotencyKey_헤더_누락시_400() throws Exception {
        Long campaignId = createCampaign(10, null);
        Long userId = createUser(MembershipTier.PRIVATE);

        mockMvc.perform(post("/api/campaigns/{campaignId}/coupons", campaignId)
                        .header("X-User-Id", userId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 캠페인이_아직_OPEN이_아니면_409() throws Exception {
        Campaign campaign = campaignRepository.save(new Campaign("아직 안 열린 캠페인", 10, null));
        couponRepository.save(new Coupon(campaign.getId(), DiscountType.FIXED,
                BigDecimal.valueOf(1000), null, null, 24));
        Long userId = createUser(MembershipTier.PRIVATE);

        mockMvc.perform(post("/api/campaigns/{campaignId}/coupons", campaign.getId())
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "idem-" + userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_OPEN"));
    }

    private Long createCampaign(int stock, MembershipTier minMembershipTier) {
        Campaign campaign = new Campaign("테스트 캠페인", stock, minMembershipTier);
        campaign.open(LocalDateTime.now(), null);
        campaign = campaignRepository.save(campaign);
        couponRepository.save(new Coupon(campaign.getId(), DiscountType.FIXED,
                BigDecimal.valueOf(1000), null, null, 24));
        return campaign.getId();
    }

    private Long createUser(MembershipTier tier) {
        String suffix = System.nanoTime() + "";
        User user = userRepository.save(new User(
                "user-" + suffix, "테스트유저", "010-0000-0000", "user-" + suffix + "@test.com", tier));
        return user.getId();
    }
}
