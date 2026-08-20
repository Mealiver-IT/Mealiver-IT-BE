package com.mealiverit.api.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 2026-08-19 실측(완판 직후 DB만 리셋 -> Redis 스냅샷이 0에 고정되어 영구 품절 오판) 대응.
// (1) DB와 어긋난 스냅샷이 재동기화로 실제 값으로 복구되는지, (2) 갱신된 스냅샷에 TTL이
// 걸려있는지(재동기화 잡 자체가 멎어도 자연 소멸하는 안전장치) 두 가지를 검증한다.
@SpringBootTest
@Testcontainers
class CampaignStockSnapshotReconciliationJobTest {

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
    private CampaignStockSnapshotReconciliationJob reconciliationJob;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CampaignStockCache campaignStockCache;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void 완판후_DB만_리셋된_상태에서도_재동기화하면_스냅샷이_DB값으로_복구된다() {
        Long campaignId = createOpenCampaign(10_000);
        // 테스트 리셋 SQL이 DB만 되돌리고 Redis는 그대로 둔 상황을 재현 - 직전 회차 완판으로 0에 고정.
        campaignStockCache.updateSnapshot(campaignId, 0);

        reconciliationJob.reconcile();

        assertThat(campaignStockCache.getSnapshot(campaignId)).isEqualTo(10_000);
    }

    @Test
    void CLOSED_캠페인은_재동기화_대상에서_제외된다() {
        Campaign campaign = new Campaign("종료 캠페인", 100, null);
        campaign.open(LocalDateTime.now(), null);
        campaign.close();
        campaign = campaignRepository.save(campaign);
        Long campaignId = campaign.getId();
        campaignStockCache.updateSnapshot(campaignId, 0);

        reconciliationJob.reconcile();

        // CLOSED는 대상이 아니므로 0으로 남아있어야 함(100으로 덮어써지지 않음).
        assertThat(campaignStockCache.getSnapshot(campaignId)).isEqualTo(0);
    }

    @Test
    void 스냅샷_갱신시_TTL이_걸린다() {
        Long campaignId = createOpenCampaign(500);

        campaignStockCache.updateSnapshot(campaignId, 500);

        Long expireSeconds = redisTemplate.getExpire("stock:" + campaignId, TimeUnit.SECONDS);
        assertThat(expireSeconds).isNotNull();
        assertThat(expireSeconds).isGreaterThan(0);
    }

    private Long createOpenCampaign(int stock) {
        Campaign campaign = new Campaign("재동기화 테스트 캠페인", stock, null);
        campaign.open(LocalDateTime.now(), null);
        return campaignRepository.save(campaign).getId();
    }
}
