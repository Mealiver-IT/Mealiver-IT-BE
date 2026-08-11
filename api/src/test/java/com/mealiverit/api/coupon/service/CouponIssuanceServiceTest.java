package com.mealiverit.api.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponRepository;
import com.mealiverit.entity.user.User;
import com.mealiverit.entity.user.UserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
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

// 04_아키텍처.txt 4.1절 V2(비관적 락)의 핵심 증명 대상: 재고보다 많은 동시 요청에도 초과발급 0건.
// Mockito가 아니라 실제 MySQL(Testcontainers)을 쓰는 이유: SELECT ... FOR UPDATE 락과
// uk_campaign_user/uk_idempotency_key 제약 위반 시 롤백 경로는 목으로는 검증할 수 없다.
@SpringBootTest
@Testcontainers
class CouponIssuanceServiceTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // V2(비관적 락)는 hot row 경합으로 요청이 직렬화된다 — 기본 풀(10)로는 100개 동시 요청이
        // 커넥션조차 못 받고 HikariCP 기본 connection-timeout(30s)에 걸려버린다. 이건 V2의 알려진
        // 실패 축(03_버전사다리_실험설계.txt 4절)이라 테스트에서는 풀/타임아웃을 넉넉히 잡아
        // "느리더라도 결과가 정확한가"만 검증한다.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "110");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "60000");
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

    @Test
    void 재고보다_많은_동시요청에도_초과발급_0건() throws InterruptedException {
        int stock = 50;
        int requesters = 100;

        Long campaignId = createCampaign(stock);
        List<Long> userIds = createUsers(requesters);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger soldOutCount = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(requesters);
        CountDownLatch ready = new CountDownLatch(requesters);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requesters);

        for (int i = 0; i < requesters; i++) {
            Long userId = userIds.get(i);
            String idempotencyKey = "issue-" + userId;
            pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    couponIssuanceService.issue(userId, campaignId, idempotencyKey);
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.SOLD_OUT) {
                        soldOutCount.incrementAndGet();
                    } else {
                        unexpected.add(e);
                    }
                } catch (Exception e) {
                    unexpected.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        boolean finishedInTime = done.await(90, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finishedInTime).as("모든 요청이 제한 시간 안에 끝났는지").isTrue();
        assertThat(unexpected).as("SOLD_OUT 외의 예상치 못한 예외").isEmpty();
        assertThat(successCount.get()).isEqualTo(stock);
        assertThat(soldOutCount.get()).isEqualTo(requesters - stock);
        assertThat(couponIssueRepository.countByCampaignId(campaignId)).isEqualTo(stock);
        assertThat(campaignRepository.findById(campaignId).orElseThrow().getRemainingStock()).isZero();
    }

    @Test
    void 동일_idempotencyKey_동시_중복요청_1건만_반영() throws InterruptedException {
        int stock = 50;
        int concurrentRetries = 20;

        Long campaignId = createCampaign(stock);
        Long userId = createUsers(1).get(0);
        String idempotencyKey = "same-key-" + userId;

        ExecutorService pool = Executors.newFixedThreadPool(concurrentRetries);
        CountDownLatch ready = new CountDownLatch(concurrentRetries);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrentRetries);

        for (int i = 0; i < concurrentRetries; i++) {
            pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    couponIssuanceService.issue(userId, campaignId, idempotencyKey);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        boolean finishedInTime = done.await(90, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finishedInTime).as("모든 요청이 제한 시간 안에 끝났는지").isTrue();
        assertThat(couponIssueRepository.countByCampaignId(campaignId)).isEqualTo(1);
        assertThat(campaignRepository.findById(campaignId).orElseThrow().getRemainingStock())
                .isEqualTo(stock - 1);
    }

    private Long createCampaign(int stock) {
        Campaign campaign = campaignRepository.save(new Campaign("테스트 캠페인", stock, null));
        couponRepository.save(new Coupon(campaign.getId(), DiscountType.FIXED,
                BigDecimal.valueOf(1000), null, null, 24));
        return campaign.getId();
    }

    private List<Long> createUsers(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String suffix = System.nanoTime() + "-" + i;
            User user = userRepository.save(new User(
                    "user-" + suffix, "테스트유저", "010-0000-0000", "user-" + suffix + "@test.com"));
            ids.add(user.getId());
        }
        return ids;
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
