package com.mealiverit.api.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

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
// 이 가드는 재고 판단에 관여하지 않는 순수 사전 필터다.
//
// 2026-08-22 명시적 release() 도입 이후 이 클래스의 검증 범위가 좁아졌다: 아래 테스트는
// "순차 호출"이라 요청이 겹치지 않고, 첫 요청이 끝나는 즉시 가드가 풀려서 실제로는 가드에
// 안 걸린다 - 검증 포인트가 "가드가 막는지"에서 "가드가 풀린 뒤 DB 제약으로 안전하게
// 복구되는지"로 바뀌었다. "요청이 실제로 겹치는 동안" 가드가 막아주는지는 이 클래스로는
// 검증 불가(동시성 재현 필요) - 그 근거는 coupon-duplicate-request-test.js 부하테스트 실측
// (2026-08-22, 5,000명×4 동시요청에서 duplicate_issued_multiple: 0)만 갖고 있다. 여기에
// JUnit 동시성 테스트가 아직 없다는 뜻이니, 유닛 테스트로도 고정해두고 싶다면 별도로 추가할 것.
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
    void 첫_요청이_끝난_뒤_도착한_같은_유저의_요청은_가드가_아니라_DB_제약으로_정상_복구된다() {
        Long campaignId = createCampaign(10);
        Long userId = createUser();

        IssueResult first = couponIssuanceService.issue(userId, campaignId, "dup-key-1");
        assertThat(first.status()).isEqualTo(IssueResult.Status.SUCCESS);

        // 첫 요청이 이미 끝났으므로 가드는 풀려있다 - 예외 없이 ALREADY_PROCESSED로 정상 반환돼야 함.
        IssueResult second = couponIssuanceService.issue(userId, campaignId, "dup-key-2");
        assertThat(second.status()).isEqualTo(IssueResult.Status.ALREADY_PROCESSED);
        assertThat(second.couponIssue().getId()).isEqualTo(first.couponIssue().getId());

        // 정상 발급 1건만 존재하고, 재고도 1개만 소진된 채로 남아있어야 함(두 번째 시도의
        // 재고 차감이 compensateStockRollback()으로 정확히 원복됐다는 증거).
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
