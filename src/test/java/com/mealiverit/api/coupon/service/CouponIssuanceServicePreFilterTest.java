package com.mealiverit.api.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import com.mealiverit.api.coupon.DiscountType;
import com.mealiverit.api.coupon.entity.Coupon;
import com.mealiverit.api.coupon.repository.CouponIssueRepository;
import com.mealiverit.api.coupon.repository.CouponRepository;
import com.mealiverit.api.user.entity.User;
import com.mealiverit.api.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 2026-08-19 멘토링 피드백(DB 선반영 -> 스냅샷 생성 -> Redis 반영)의 "사전 필터" 부분 검증.
// 이 필터는 재고 여부를 직접 결정하지 않는다(그건 여전히 DB) - 확실히 품절인 경우에만
// DB까지 안 보내고 미리 거르는 최적화다. 그래서 검증 포인트도 두 가지: (1) 캐시가 확실히
// 0이면 DB를 아예 안 건드리고 즉시 거절하는지, (2) 캐시가 비어 있으면(미스) 평소처럼 DB로
// 정상 처리되는지(폴백이 안전한지).
@SpringBootTest
@Testcontainers
class CouponIssuanceServicePreFilterTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private CouponIssuanceService couponIssuanceService;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private CampaignStockCache campaignStockCache;

    @Test
    void Redis_스냅샷이_0이면_DB_재고와_무관하게_즉시_품절() {
        // DB 재고는 5장 남아있어서, 필터가 없었다면 정상 발급됐을 상황.
        Long campaignId = createCampaign(5);
        Long userId = createUser();
        campaignStockCache.updateSnapshot(campaignId, 0);

        assertThatThrownBy(() -> couponIssuanceService.issue(userId, campaignId, "prefilter-key-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.SOLD_OUT));

        // DB까지 안 갔다는 증거 - coupon_issue row가 하나도 안 생기고, remainingStock도 그대로 5.
        assertThat(couponIssueRepository.countByCampaignId(campaignId)).isZero();
        assertThat(campaignRepository.findById(campaignId).orElseThrow().getRemainingStock()).isEqualTo(5);
    }

    @Test
    void Redis_스냅샷이_없으면_캐시_미스로_DB로_정상_처리() {
        Long campaignId = createCampaign(5);
        Long userId = createUser();
        // campaignStockCache에 아무것도 안 써서 캐시 미스(null) 상태를 그대로 둔다.

        IssueResult result = couponIssuanceService.issue(userId, campaignId, "prefilter-key-2");

        assertThat(result.status()).isEqualTo(IssueResult.Status.SUCCESS);
        assertThat(couponIssueRepository.countByCampaignId(campaignId)).isEqualTo(1);
    }

    private Long createCampaign(int stock) {
        Campaign campaign = new Campaign("사전필터 테스트 캠페인", stock, null);
        campaign.open(LocalDateTime.now(), null);
        campaign = campaignRepository.save(campaign);
        couponRepository.save(new Coupon(campaign.getId(), DiscountType.FIXED,
                BigDecimal.valueOf(1000), null, null, 24));
        return campaign.getId();
    }

    private Long createUser() {
        String suffix = System.nanoTime() + "";
        User user = userRepository.save(new User(
                "user-" + suffix, "테스트유저", "010-0000-0000", "user-" + suffix + "@test.com"));
        return user.getId();
    }
}
