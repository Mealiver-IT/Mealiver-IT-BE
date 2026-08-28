package com.mealiverit.api.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mealiverit.api.verification.report.SlackNotifier;
import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import com.mealiverit.api.campaign.entity.CampaignStockShard;
import com.mealiverit.api.campaign.repository.CampaignStockShardRepository;
import com.mealiverit.api.coupon.DiscountType;
import com.mealiverit.api.coupon.entity.Coupon;
import com.mealiverit.api.coupon.entity.CouponIssue;
import com.mealiverit.api.coupon.repository.CouponIssueRepository;
import com.mealiverit.api.coupon.repository.CouponRepository;
import com.mealiverit.api.user.MembershipTier;
import com.mealiverit.api.user.entity.User;
import com.mealiverit.api.user.repository.UserRepository;
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
// 2026-08-27 1차 실측(부하테스트 담당자 리포트, 캠페인 1284): repair()가 부족(deficit)을 발견하는
// 즉시 복구하던 게, 2만 건 동시요청 상황에서 "재고는 차감됐지만 coupon_issue INSERT는 아직
// 커밋 전"인 정상적인 처리 중 상태를 영구 유실로 오판해 오히려 초과발급을 일으켰다(재동기화
// 진단 로그로 샤드 합계가 60초 주기마다 2 -> 19 -> 41로 계속 늘어나는 게 실측됨). "연속 두 번
// 관측"돼야 확정하는 디바운스로 1차 수정했었다.
//
// 2026-08-28 2차 실측(팀원 분석, 캠페인 1315 재재현): 그 디바운스도 "같은 부족이 지속되는가"가
// 아니라 "뭐든 부족해 보이는 게 두 번 연속인가"만 봐서, 트래픽이 오래 지속되는 부하테스트에서는
// 서로 다른 원인의 부족이 우연히 두 번 연속 관측돼 여전히 오판할 수 있었다 - StockLossRepairJob
// 클래스 상단 주석 참고. repair()(주기 검증)에서는 부족 방향 자동 복구 자체를 완전히 없앴다 -
// 이제 관측만 로그로 남기고, 실제 복구는 항상 checkOnClose()(캠페인 종료 시 1회)에서만 한다.
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
    void 재고가_부족한_방향의_불일치는_주기_검증에서는_절대_복구되지_않는다() {
        // total_stock=100인데 샤드 합계가 97, 발급 건수는 0 -> compensateStockRollback()마저
        // 실패해서 3개가 영구 유실된 것처럼 보이는 상황을 재현. 진짜 영구 유실이라도 주기
        // 검증(repair())은 관측만 하고 절대 복구하지 않는다 - 몇 번을 다시 불러도 마찬가지다.
        // (2026-08-28: "연속 두 번 관측되면 복구" 디바운스 자체를 없앴다 - 클래스 상단 주석 참고.
        // 실제 복구는 캠페인 종료 시 checkOnClose()에서만 한다.)
        Long campaignId = createOpenCampaign(100);
        campaignStockShardRepository.save(new CampaignStockShard(campaignId, 0, 97, 100));

        stockLossRepairJob.repair();
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(97);

        stockLossRepairJob.repair();
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(97);

        stockLossRepairJob.repair();
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(97);
        verifyNoInteractions(slackNotifier);
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
