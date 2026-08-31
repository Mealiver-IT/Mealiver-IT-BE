package com.mealiverit.api.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mealiverit.api.coupon.service.CouponIssueService;
import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import com.mealiverit.api.coupon.DiscountType;
import com.mealiverit.api.coupon.entity.Coupon;
import com.mealiverit.api.coupon.entity.CouponIssue;
import com.mealiverit.api.coupon.repository.CouponIssueRepository;
import com.mealiverit.api.coupon.repository.CouponRepository;
import com.mealiverit.api.user.MembershipTier;
import com.mealiverit.api.user.entity.User;
import com.mealiverit.api.user.repository.UserRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// GET /api/members/me/coupons/all의 3일 유예 규칙 검증.
// usedAt/canceledAt은 markUsed()/markCanceled()가 항상 LocalDateTime.now()로 고정해서 과거 날짜를 직접 못 넣으므로, JdbcTemplate으로 저장 후 타임스탬프를 직접 되돌림
// expiredAt은 markExpired()가 파라미터로 받으므로 그대로 원하는 날짜를 넣으면 됨
@SpringBootTest
@AutoConfigureMockMvc
public class CouponFullHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private CouponIssueService couponIssueService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 사용가능_쿠폰은_항상_노출() throws Exception {
        Long userId = createUser();
        Long issueId = createIssuedCoupon(userId);

        assertThat(fetchAllCoupons(userId)).contains(issueId);
    }

    @Test
    void 사용한지_2일된_쿠폰은_노출() throws Exception {
        Long userId = createUser();
        Long issueId = createUsedCoupon(userId);
        backdateColumn("used_at", issueId, 2);

        assertThat(fetchAllCoupons(userId)).contains(issueId);
    }

    @Test
    void 사용한지_4일된_쿠폰은_미노출() throws Exception {
        Long userId = createUser();
        Long issueId = createUsedCoupon(userId);
        backdateColumn("used_at", issueId, 4);

        assertThat(fetchAllCoupons(userId)).doesNotContain(issueId);
    }

    @Test
    void 회수된지_2일된_쿠폰은_노출() throws Exception {
        Long userId = createUser();
        Long issueId = createCanceledCoupon(userId);
        backdateColumn("canceled_at", issueId, 2);

        assertThat(fetchAllCoupons(userId)).contains(issueId);
    }

    @Test
    void 회수된지_4일된_쿠폰은_미노출() throws Exception {
        Long userId = createUser();
        Long issueId = createCanceledCoupon(userId);
        backdateColumn("canceled_at", issueId, 4);

        assertThat(fetchAllCoupons(userId)).doesNotContain(issueId);
    }

    @Test
    void 만료된지_2일된_쿠폰은_노출() throws Exception {
        Long userId = createUser();
        Long issueId = createExpiredCoupon(userId, LocalDateTime.now().minusDays(2));

        assertThat(fetchAllCoupons(userId)).contains(issueId);
    }

    @Test
    void 만료된지_4일된_쿠폰은_미노출() throws Exception {
        Long userId = createUser();
        Long issueId = createExpiredCoupon(userId, LocalDateTime.now().minusDays(4));

        assertThat(fetchAllCoupons(userId)).doesNotContain(issueId);
    }

    // 응답 body의 data 배열에서 id만 뽑아 리스트로 반환 - contains/doesNotContain으로 노출 여부만 검증
    private List<Long> fetchAllCoupons(Long userId) throws Exception {
        String body = mockMvc.perform(get("/api/members/me/coupons/all")
                        .header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).get("data");
        List<Long> ids = new ArrayList<>();
        data.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    // 도메인 메서드로는 과거 날짜를 못 넣는 필드(used_at/canceled_at)를 테스트에서 강제로 되돌림
    private void backdateColumn(String column, Long issueId, int daysAgo) {
        jdbcTemplate.update(
                "UPDATE coupon_issue SET " + column + " = ? WHERE id = ?",
                LocalDateTime.now().minusDays(daysAgo), issueId);
    }

    private Long createUser() {
        User user = userRepository.save(new User(
                "history-user-" + System.nanoTime(), "테스트 유저", "010-0000-0000",
                "history-" + System.nanoTime() + "@test.com"));
        return user.getId();
    }

    private Long createIssuedCoupon(Long userId) {
        Campaign campaign = campaignRepository.save(new Campaign("전체조회 테스트 캠페인", 10, null));
        Coupon coupon = couponRepository.save(new Coupon(campaign.getId(), DiscountType.FIXED, BigDecimal.valueOf(1000), null, null, 24));
        CouponIssue issue = CouponIssue.issue(campaign.getId(), userId, "idem-" + UUID.randomUUID(),
                coupon, MembershipTier.PRIVATE, BigDecimal.valueOf(1000));
        return couponIssueRepository.save(issue).getId();
    }

    private Long createUsedCoupon(Long userId) {
        Long issueId = createIssuedCoupon(userId);
        couponIssueService.markUsed(issueId, "setup-used-" + UUID.randomUUID());
        return issueId;
    }

    private Long createCanceledCoupon(Long userId) {
        Long issueId = createIssuedCoupon(userId);
        couponIssueService.markCanceled(issueId, "setup-canceled-" + UUID.randomUUID());
        return issueId;
    }

    // markExpired()는 파라미터로 날짜를 받으므로 backdateColumn 없이 바로 원하는 시점을 넣을 수 있음
    private Long createExpiredCoupon(Long userId, LocalDateTime expiredAt) {
        Long issueId = createIssuedCoupon(userId);
        CouponIssue issue = couponIssueRepository.findById(issueId).orElseThrow();
        issue.markExpired(expiredAt);
        couponIssueRepository.save(issue);
        return issueId;
    }
}