package com.mealiverit.api.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import com.mealiverit.api.campaign.entity.CampaignStockShard;
import com.mealiverit.api.campaign.repository.CampaignStockShardRepository;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// 2026-08-19 실측(완판 직후 DB만 리셋 -> Redis 스냅샷이 0에 고정되어 영구 품절 오판) 대응.
// (1) DB와 어긋난 스냅샷이 재동기화로 실제 값으로 복구되는지, (2) 갱신된 스냅샷에 TTL이
// 걸려있는지(재동기화 잡 자체가 멎어도 자연 소멸하는 안전장치) 두 가지를 검증한다.
//
// 2026-08-26 추가: reconcile()이 캠페인 한 건씩 REQUIRES_NEW 트랜잭션으로 처리하는지(한 캠페인
// 실패가 나머지에 전파되지 않는지)도 검증한다 - 부하테스트 담당자가 리포트한 "remainingStock이
// 테스트 내내 한 번도 안 바뀜" 현상의 유력한 원인이었다(전체를 하나의 트랜잭션으로 묶어서 한
// 캠페인 실패가 사이클 전체를 롤백시켰음).
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
    private CampaignStockShardRepository campaignStockShardRepository;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @MockitoSpyBean
    private CampaignRepository campaignRepositorySpy;

    @Test
    void 완판후_샤드테이블만_리셋된_상태에서도_재동기화하면_스냅샷이_샤드합계로_복구된다() {
        Long campaignId = createOpenCampaign(300);
        // 직전 회차가 완판돼 샤드가 0, Redis 스냅샷도 정확히 0으로 기록된 상태를 재현.
        campaignStockShardRepository.save(new CampaignStockShard(campaignId, 0, 0, 300));
        campaignStockCache.updateSnapshot(campaignId, 0);

        // 테스트 리셋 스크립트가 "진짜 재고"인 샤드 테이블 값을 300으로 직접 되돌리고 Redis는
        // 그대로 둔 상황 - deleteAll 후 재삽입으로 리셋 스크립트의 UPDATE를 흉내낸다.
        campaignStockShardRepository.deleteAll();
        campaignStockShardRepository.save(new CampaignStockShard(campaignId, 0, 300, 300));

        reconciliationJob.reconcile();

        assertThat(campaignStockCache.getSnapshot(campaignId)).isEqualTo(300);
        assertThat(campaignRepository.findById(campaignId).orElseThrow().getRemainingStock()).isEqualTo(300);
    }

    @Test
    void 샤드가_아직_생성되지_않은_캠페인은_재동기화_대상에서_제외된다() {
        // 예약 시도가 한 번도 없어 샤드가 지연 생성되지 않은 상태 - 재동기화가 이 캠페인을
        // 건드리면 합계(0)로 오판해 멀쩡한 신규 캠페인을 품절 처리하게 된다.
        Long campaignId = createOpenCampaign(10_000);
        campaignStockCache.updateSnapshot(campaignId, 500); // 임의의 값으로 캐시 미스가 아님을 확인

        reconciliationJob.reconcile();

        assertThat(campaignStockCache.getSnapshot(campaignId)).isEqualTo(500);
        assertThat(campaignRepository.findById(campaignId).orElseThrow().getRemainingStock()).isEqualTo(10_000);
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

    @Test
    void 한_캠페인의_재동기화_실패가_다른_캠페인_갱신을_막지_않는다() {
        Long ok = createOpenCampaign(300);
        Long broken = createOpenCampaign(300);
        campaignStockShardRepository.save(new CampaignStockShard(ok, 0, 200, 300));
        campaignStockShardRepository.save(new CampaignStockShard(broken, 0, 250, 300));

        // 극단적 동시성 상황에서 HikariCP 커넥션 획득 실패 등으로 한 캠페인 처리 중 예외가 나는
        // 상황을 재현 - reconcile() 전체가 하나의 트랜잭션이었다면 이 예외로 ok 캠페인의 갱신까지
        // 롤백됐을 것이다.
        doThrow(new RuntimeException("의도적 실패(테스트) - 커넥션 획득 실패 상황 재현"))
                .when(campaignRepositorySpy).setRemainingStock(eq(broken), anyInt());

        reconciliationJob.reconcile();

        assertThat(campaignStockCache.getSnapshot(ok)).isEqualTo(200);
        assertThat(campaignRepository.findById(ok).orElseThrow().getRemainingStock()).isEqualTo(200);
        // broken은 이번 사이클엔 실패했어야 하고(다음 15초 주기에 재시도), 갱신 안 된 채로 남아야 한다.
        assertThat(campaignStockCache.getSnapshot(broken)).isNull();
        assertThat(campaignRepository.findById(broken).orElseThrow().getRemainingStock()).isEqualTo(300);
    }

    private Long createOpenCampaign(int stock) {
        Campaign campaign = new Campaign("재동기화 테스트 캠페인", stock, null);
        campaign.open(LocalDateTime.now(), null);
        return campaignRepository.save(campaign).getId();
    }
}
