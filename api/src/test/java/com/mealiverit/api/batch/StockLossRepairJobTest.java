package com.mealiverit.api.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mealiverit.api.verification.report.SlackNotifier;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.campaign.CampaignStockShard;
import com.mealiverit.entity.campaign.CampaignStockShardRepository;
import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponRepository;
import com.mealiverit.entity.user.MembershipTier;
import com.mealiverit.entity.user.User;
import com.mealiverit.entity.user.UserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// "발급 실패 재처리" 요청(2026-08-24)으로 구현. CouponIssuanceService.compensateStockRollbackSafely()가
// 스스로 인정하는 구멍 - 재고 차감 후 발급도 실패하고 그 롤백 자체도 실패하면(HikariCP 풀 고갈 등)
// 재고가 영구히 유실됨 - 을 탐지·복구하는 배치를 검증한다. 안전장치(초과 방향은 자동으로 안 건드림)도
// 같이 검증한다.
//
// 2026-08-26: 주기 검증(repair())과 종료 시 최종 검증(checkOnClose())의 Slack 알림 정책 차이도
// 검증한다 - repair()는 절대 Slack을 보내지 않고, checkOnClose()는 불일치를 찾았을 때만 정확히
// 한 번 보낸다(SlackNotifier를 MockitoBean으로 대체해 실제 웹훅 호출 없이 호출 여부만 검증).
//
// 2026-08-27 실측(부하테스트 담당자 리포트, 캠페인 1284): repair()가 부족(deficit)을 발견하는
// 즉시 복구하던 게, 2만 건 동시요청 상황에서 "재고는 차감됐지만 coupon_issue INSERT는 아직
// 커밋 전"인 정상적인 처리 중 상태를 영구 유실로 오판해 오히려 초과발급을 일으켰다(재동기화
// 진단 로그로 샤드 합계가 60초 주기마다 2 -> 19 -> 41로 계속 늘어나는 게 실측됨). repair()가
// 같은 캠페인의 부족을 "연속 두 번" 관측해야만 확정·복구하도록 고친 뒤로, 이 클래스의 부족
// 관련 테스트들은 repair()를 두 번 호출해야 실제 복구를 확인할 수 있다.
@SpringBootTest
@Testcontainers
class StockLossRepairJobTest {

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
    private StockLossRepairJob stockLossRepairJob;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CampaignStockShardRepository campaignStockShardRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private UserRepository userRepository;
    @MockitoBean
    private SlackNotifier slackNotifier;

    @Test
    void 재고가_부족한_방향의_불일치는_연속_두번_관측돼야_복구된다() {
        // total_stock=100인데 샤드 합계가 97, 발급 건수는 0 -> compensateStockRollback()마저
        // 실패해서 3개가 영구 유실된 상황을 재현.
        Long campaignId = createOpenCampaign(100);
        campaignStockShardRepository.save(new CampaignStockShard(campaignId, 0, 97, 100));

        stockLossRepairJob.repair();
        // 첫 관측 - 아직 처리 중인 요청일 수 있으니 이번 주기엔 손대지 않아야 한다.
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(97);

        stockLossRepairJob.repair();
        // 다음 주기에도 여전히 부족 - 진짜 유실로 확정하고 복구해야 한다.
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(100);
    }

    @Test
    void 첫_관측_이후_부족이_저절로_해소되면_복구하지_않고_다음_부족은_처음부터_다시_관측한다() {
        // 실전 시나리오 재현: "처리 중"이던 요청들이 다음 주기 전에 정상 커밋을 마쳐서 부족이
        // 저절로 사라지는 경우 - 이때 복구가 일어나면 안 된다(그게 바로 초과발급 원인이었음).
        Long campaignId = createOpenCampaign(100);
        campaignStockShardRepository.save(new CampaignStockShard(campaignId, 0, 97, 100));
        Coupon coupon = couponRepository.save(new Coupon(campaignId, DiscountType.FIXED,
                java.math.BigDecimal.valueOf(1000), null, null, 24));

        stockLossRepairJob.repair(); // 1회차: 부족(3) 최초 관측, 미복구
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(97);

        // 그 사이 처리 중이던 요청 3건이 정상적으로 커밋을 마쳐 불변식이 다시 맞아떨어진 상황을
        // 흉내낸다(97 + 발급 3건 = 100) - 실제로 재고가 샌 게 아니라 발급 반영이 늦었을 뿐이다.
        for (int i = 0; i < 3; i++) {
            issueCoupon(campaignId, coupon);
        }

        stockLossRepairJob.repair(); // 2회차: 불변식이 맞아 대상에서 아예 빠짐 - 복구 없음
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(97);

        // 완전히 새로운 부족(2)이 나중에 또 생겨도, 지난번 pending 상태가 남아있어서 곧바로
        // 확정·복구되면 안 된다 - 이번에도 "최초 관측"부터 다시 시작해야 한다.
        campaignStockShardRepository.decreaseIfAvailable(campaignId, 0);
        campaignStockShardRepository.decreaseIfAvailable(campaignId, 0);

        stockLossRepairJob.repair(); // 3회차: 새 부족 최초 관측 - 미복구
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(95);

        stockLossRepairJob.repair(); // 4회차: 연속 두 번째 관측 - 확정·복구
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(97);
    }

    private void issueCoupon(Long campaignId, Coupon coupon) {
        Long userId = userRepository.save(new User("user-" + UUID.randomUUID(), "테스터",
                "010-0000-0000", UUID.randomUUID() + "@example.com")).getId();
        couponIssueRepository.save(CouponIssue.issue(campaignId, userId, "idem-" + UUID.randomUUID(),
                coupon, MembershipTier.PRIVATE, coupon.getDiscountValue()));
    }

    @Test
    void 재고가_남는_방향의_불일치는_자동으로_건드리지_않는다() {
        // total_stock=100, 발급 0건인데 샤드 합계가 105 - 정상적으로는 나올 수 없는 반대 방향.
        // 다른 종류의 버그일 수 있으므로 자동 복구 대상이 아니어야 한다.
        Long campaignId = createOpenCampaign(100);
        campaignStockShardRepository.save(new CampaignStockShard(campaignId, 0, 105, 100));

        stockLossRepairJob.repair();

        int untouched = campaignStockShardRepository.sumRemainingStock(campaignId);
        assertThat(untouched).isEqualTo(105);
    }

    @Test
    void 발급건수까지_포함해_불변식이_맞으면_아무것도_건드리지_않는다() {
        // total_stock=100, 실제 발급 1건 + 샤드 잔여 99 -> 정상 상태(1 + 99 = 100).
        Long campaignId = createOpenCampaign(100);
        campaignStockShardRepository.save(new CampaignStockShard(campaignId, 0, 99, 100));
        Long userId = userRepository.save(new User("user-" + UUID.randomUUID(), "테스터",
                "010-0000-0000", "tester@example.com")).getId();
        Coupon coupon = couponRepository.save(new Coupon(campaignId, DiscountType.FIXED,
                java.math.BigDecimal.valueOf(1000), null, null, 24));
        couponIssueRepository.save(CouponIssue.issue(campaignId, userId, "idem-" + UUID.randomUUID(),
                coupon, MembershipTier.PRIVATE, coupon.getDiscountValue()));

        stockLossRepairJob.repair();

        int unchanged = campaignStockShardRepository.sumRemainingStock(campaignId);
        assertThat(unchanged).isEqualTo(99);
    }

    @Test
    void repair는_CLOSED_캠페인을_주기_검증_대상에서_제외한다() {
        // 재고가 부족한 방향이라도(자동 복구 가능한 방향) CLOSED로 전환된 캠페인은 checkOnClose()가
        // 전환 시점에 이미 마지막 검증을 했다고 가정하고 repair()는 더는 건드리지 않아야 한다.
        Long campaignId = createOpenCampaign(100);
        campaignStockShardRepository.save(new CampaignStockShard(campaignId, 0, 97, 100));
        closeCampaign(campaignId);

        stockLossRepairJob.repair();

        int untouched = campaignStockShardRepository.sumRemainingStock(campaignId);
        assertThat(untouched).isEqualTo(97);
        verifyNoInteractions(slackNotifier);
    }

    @Test
    void checkOnClose는_부족한_방향을_복구하고_Slack으로_한_번만_알린다() {
        Long campaignId = createOpenCampaign(100);
        campaignStockShardRepository.save(new CampaignStockShard(campaignId, 0, 97, 100));
        closeCampaign(campaignId);

        stockLossRepairJob.checkOnClose(campaignId);

        int repaired = campaignStockShardRepository.sumRemainingStock(campaignId);
        assertThat(repaired).isEqualTo(100);
        verify(slackNotifier, times(1)).send(any(), any());
    }

    @Test
    void checkOnClose는_남는_방향을_건드리지_않고_Slack으로_한_번만_알린다() {
        Long campaignId = createOpenCampaign(100);
        campaignStockShardRepository.save(new CampaignStockShard(campaignId, 0, 105, 100));
        closeCampaign(campaignId);

        stockLossRepairJob.checkOnClose(campaignId);

        int untouched = campaignStockShardRepository.sumRemainingStock(campaignId);
        assertThat(untouched).isEqualTo(105);
        verify(slackNotifier, times(1)).send(any(), any());
    }

    @Test
    void checkOnClose는_불일치가_없으면_Slack을_보내지_않는다() {
        Long campaignId = createOpenCampaign(100);
        campaignStockShardRepository.save(new CampaignStockShard(campaignId, 0, 100, 100));
        closeCampaign(campaignId);

        stockLossRepairJob.checkOnClose(campaignId);

        verifyNoInteractions(slackNotifier);
    }

    private Long createOpenCampaign(int stock) {
        Campaign campaign = new Campaign("재고유실 테스트 캠페인", stock, null);
        campaign.open(LocalDateTime.now(), null);
        return campaignRepository.save(campaign).getId();
    }

    private void closeCampaign(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId).orElseThrow();
        campaign.close();
        campaignRepository.save(campaign);
    }
}
