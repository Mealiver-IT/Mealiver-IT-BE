package com.mealiverit.api.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.campaign.CampaignStockShardRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 2026-08-20 재고 샤딩 - hot row 처리량 자체를 늘리기 위해 campaign 재고를 여러 row(샤드)로 쪼갠
// 전략. 검증 포인트: (1) 지연 생성이 campaign.remaining_stock 기준으로 정확히 되는지, (2) 특정
// 샤드가 비어도 다른 샤드로 폴백해서 정상 처리되는지, (3) 전 샤드 소진 시 정확히 거절되는지,
// (4) 여러 샤드에 걸쳐서도 동시경합 시 초과발급이 없는지.
@SpringBootTest
@Testcontainers
class ShardedStockReservationStrategyTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "110");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "60000");
    }

    @Autowired
    private ShardedStockReservationStrategy strategy;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CampaignStockShardRepository shardRepository;

    @Test
    void 첫_reserve_호출시_campaign_remainingStock_기준으로_샤드가_지연생성된다() {
        Long campaignId = createCampaign(23);

        assertThat(shardRepository.existsByCampaignId(campaignId)).isFalse();

        boolean reserved = strategy.reserve(campaignId);

        assertThat(reserved).isTrue();
        assertThat(shardRepository.existsByCampaignId(campaignId)).isTrue();
        // 23을 10개 샤드로 나누면 합계는 그대로 23이어야 하고, 방금 1개 예약했으니 22.
        assertThat(shardRepository.sumRemainingStock(campaignId)).isEqualTo(22);
    }

    @Test
    void 특정_샤드가_비어있어도_다른_샤드로_폴백해서_예약된다() {
        Long campaignId = createCampaign(10);
        strategy.reserve(campaignId); // 지연 생성 트리거 - 랜덤으로 고른 샤드 하나가 이미 소진됨

        // 어느 샤드가 트리거로 이미 소진됐는지 모르므로, 0~9번을 전부 무조건 한 번씩 비운다
        // (샤드당 capacity가 1이라 이미 0인 샤드는 그냥 무효과).
        for (int i = 0; i < 10; i++) {
            shardRepository.decreaseIfAvailable(campaignId, i);
        }
        assertThat(shardRepository.sumRemainingStock(campaignId)).isZero();
        // 이제 0번 샤드에만 재고 1을 명시적으로 채워서 "0번만 재고가 있는 상황"을 결정론적으로 만든다.
        shardRepository.increaseIfBelowCapacity(campaignId, 0);
        assertThat(shardRepository.sumRemainingStock(campaignId)).isEqualTo(1);

        boolean reserved = strategy.reserve(campaignId);

        assertThat(reserved).isTrue();
        assertThat(shardRepository.sumRemainingStock(campaignId)).isZero();
    }

    @Test
    void 모든_샤드가_소진되면_거절된다() {
        Long campaignId = createCampaign(5);
        strategy.reserve(campaignId); // 지연 생성 트리거

        for (int i = 0; i < 10; i++) {
            shardRepository.decreaseIfAvailable(campaignId, i); // 이미 0인 샤드는 그냥 무효과
        }
        assertThat(shardRepository.sumRemainingStock(campaignId)).isZero();

        boolean reserved = strategy.reserve(campaignId);

        assertThat(reserved).isFalse();
    }

    @Test
    void reserve_후_rollback하면_합계가_원복된다() {
        Long campaignId = createCampaign(30);

        strategy.reserve(campaignId);
        assertThat(shardRepository.sumRemainingStock(campaignId)).isEqualTo(29);

        strategy.rollback(campaignId);

        assertThat(shardRepository.sumRemainingStock(campaignId)).isEqualTo(30);
    }

    @Test
    void 여러_샤드에_걸쳐도_동시경합시_초과발급이_없다() throws InterruptedException {
        int stock = 50;
        int requesters = 100;
        Long campaignId = createCampaign(stock);

        AtomicInteger successCount = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(requesters);
        CountDownLatch ready = new CountDownLatch(requesters);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requesters);

        List<Boolean> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        for (int i = 0; i < requesters; i++) {
            pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    boolean reserved = strategy.reserve(campaignId);
                    results.add(reserved);
                    if (reserved) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        boolean finishedInTime = done.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finishedInTime).isTrue();
        assertThat(successCount.get()).isEqualTo(stock);
        assertThat(shardRepository.sumRemainingStock(campaignId)).isZero();
    }

    private Long createCampaign(int stock) {
        Campaign campaign = new Campaign("샤딩 테스트 캠페인", stock, null);
        campaign.open(LocalDateTime.now(), null);
        return campaignRepository.save(campaign).getId();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
