package com.mealiverit.api.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.campaign.CampaignStockShardRepository;
import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponRepository;
import com.mealiverit.entity.user.User;
import com.mealiverit.entity.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
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

// 2026-08-19 부하테스트(coupon_mixed_5k_x4.js) 실측 - 같은 유저의 거의 동시 중복요청(서로 다른
// idempotencyKey)이 캠페인 락을 반복 획득/롤백-재획득하며 hot row 경합을 증폭시키던 문제 대응.
// 이 가드는 재고 판단에 관여하지 않는 순수 사전 필터라, 검증 포인트도 "DB(락)까지 아예 안 갔는지"다.
@SpringBootTest
@Testcontainers
class CouponIssuanceServiceDuplicateGuardTest {

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
    private CampaignStockShardRepository campaignStockShardRepository;

    @Test
    void 같은_유저의_거의_동시_중복요청은_캠페인_락_없이_즉시_거절된다() {
        Long campaignId = createCampaign(10);
        Long userId = createUser();

        IssueResult first = couponIssuanceService.issue(userId, campaignId, "dup-key-1");
        assertThat(first.status()).isEqualTo(IssueResult.Status.SUCCESS);

        // 같은 (campaignId, userId)에 대해 다른 idempotencyKey로 온 두 번째 요청 - 가드에 막혀야 함.
        assertThatThrownBy(() -> couponIssuanceService.issue(userId, campaignId, "dup-key-2"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.DUPLICATE_REQUEST_IN_PROGRESS));

        // 정상 발급 1건만 존재하고, 재고도 1개만 소진됐어야 함(가드가 DB까지 안 갔다는 증거).
        // campaign.remainingStock이 아니라 샤드 합계로 확인한다 - 전자는 비동기 리스너가 사후에
        // 채워주는 값이라 동시성 검증 직후에는 아직 반영 전일 수 있다(2026-08-20 재고 샤딩).
        assertThat(couponIssueRepository.countByCampaignId(campaignId)).isEqualTo(1);
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(9);
    }

    @Test
    void 다른_유저의_요청은_가드의_영향을_받지_않는다() {
        Long campaignId = createCampaign(10);
        Long userA = createUser();
        Long userB = createUser();

        couponIssuanceService.issue(userA, campaignId, "dup-key-a");
        IssueResult resultB = couponIssuanceService.issue(userB, campaignId, "dup-key-b");

        assertThat(resultB.status()).isEqualTo(IssueResult.Status.SUCCESS);
        assertThat(couponIssueRepository.countByCampaignId(campaignId)).isEqualTo(2);
    }

    private Long createCampaign(int stock) {
        Campaign campaign = new Campaign("중복요청가드 테스트 캠페인", stock, null);
        campaign.open(LocalDateTime.now(), null);
        campaign = campaignRepository.save(campaign);
        couponRepository.save(new Coupon(campaign.getId(), DiscountType.FIXED,
                BigDecimal.valueOf(1000), null, null, 24));
        return campaign.getId();
    }

    private Long createUser() {
        String suffix = UUID.randomUUID().toString();
        User user = userRepository.save(new User(
                "user-" + suffix, "테스트유저", "010-0000-0000", "user-" + suffix + "@test.com"));
        return user.getId();
    }
}
