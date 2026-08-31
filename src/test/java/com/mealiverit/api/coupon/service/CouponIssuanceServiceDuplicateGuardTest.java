package com.mealiverit.api.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import com.mealiverit.api.campaign.repository.CampaignStockShardRepository;
import com.mealiverit.api.coupon.DiscountType;
import com.mealiverit.api.coupon.entity.Coupon;
import com.mealiverit.api.coupon.repository.CouponIssueRepository;
import com.mealiverit.api.coupon.repository.CouponRepository;
import com.mealiverit.api.user.entity.User;
import com.mealiverit.api.user.repository.UserRepository;
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
// 2026-08-22 최소 보유시간(MIN_HOLD) + release() 결합 도입 이후: 처리가 아무리 빨리 끝나도
// 최소 MIN_HOLD(10초)만큼은 가드가 유지된다(CouponIssuanceDuplicateGuard 주석 참고). 이 테스트는
// 순차 호출이지만 두 호출 사이 간격이 MIN_HOLD보다 훨씬 짧으므로(테스트 실행 시간은 보통 수십ms),
// 두 번째 요청은 여전히 가드에 막힌다 - "요청이 실제로 겹치는 동안"뿐 아니라 "빨리 끝난 직후"도
// 보호된다는 걸 이 테스트가 증명한다. MIN_HOLD를 넘긴 뒤에도 가드가 즉시 풀리는지(release()의
// 핵심 목적)는 이 클래스로는 검증 불가(10초 이상 대기하거나 시간을 조작해야 함) - 그 근거는
// coupon-duplicate-request-test.js 부하테스트 실측만 갖고 있다.
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
    void 같은_유저의_직후_재요청은_MIN_HOLD_안에서는_캠페인_락_없이_즉시_거절된다() {
        Long campaignId = createCampaign(10);
        Long userId = createUser();

        IssueResult first = couponIssuanceService.issue(userId, campaignId, "dup-key-1");
        assertThat(first.status()).isEqualTo(IssueResult.Status.SUCCESS);

        // 첫 요청이 끝난 지 얼마 안 됐으므로(MIN_HOLD=10초 이내) 가드가 아직 유지 중이어야 함.
        assertThatThrownBy(() -> couponIssuanceService.issue(userId, campaignId, "dup-key-2"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.DUPLICATE_REQUEST_IN_PROGRESS));

        // 정상 발급 1건만 존재하고, 재고도 1개만 소진됐어야 함(가드가 DB까지 안 갔다는 증거).
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
