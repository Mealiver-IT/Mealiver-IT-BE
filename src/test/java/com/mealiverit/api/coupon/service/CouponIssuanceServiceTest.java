package com.mealiverit.api.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

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
    private CampaignStockShardRepository campaignStockShardRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

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
        // campaign.remainingStock이 아니라 샤드 합계로 확인한다 - 전자는 비동기 리스너가 사후에
        // 채워주는 값이라 동시성 검증 직후에는 아직 반영 전일 수 있다(2026-08-20 재고 샤딩).
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isZero();
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
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(stock - 1);
    }

    @Test
    void 동일_idempotencyKey_순차_재요청시_새_INSERT_없이_같은_쿠폰_반환() {
        Long campaignId = createCampaign(50);
        Long userId = createUsers(1).get(0);
        String idempotencyKey = "sequential-retry-key";

        IssueResult first = couponIssuanceService.issue(userId, campaignId, idempotencyKey);
        IssueResult second = couponIssuanceService.issue(userId, campaignId, idempotencyKey);

        assertThat(first.status()).isEqualTo(IssueResult.Status.SUCCESS);
        assertThat(second.status()).isEqualTo(IssueResult.Status.ALREADY_PROCESSED);
        // findByIdempotencyKey() fast-path로 곧장 반환되는지 — 같은 id/couponCode를 재사용해야
        // 두 번째 호출이 DB에 새 row를 만들지 않고(재고를 또 깎지 않고) 그대로 반환했다는 뜻이다.
        assertThat(second.couponIssue().getId()).isEqualTo(first.couponIssue().getId());
        assertThat(second.couponIssue().getCouponCode()).isEqualTo(first.couponIssue().getCouponCode());
        assertThat(couponIssueRepository.countByCampaignId(campaignId)).isEqualTo(1);
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(49);
    }

    // 2026-08-27: recoverFromInsertFailure() 수정 검증 - "이미 발급받은 유저가 다른
    // idempotencyKey로 다시 요청"(예: 클라이언트 타임아웃 후 재시도)하는 진짜 경합 케이스에서,
    // 이번 시도가 확보한 재고가 제대로 복원되는지 확인한다. (campaignId, userId)만으로 "이미
    // 처리됨"을 판단했다면(수정 전 초안의 버그) 이 시도의 예약분이 영원히 안 풀려서 재고가
    // 샌다 - stock이 9가 아니라 8로 남는지가 이 회귀의 신호다.
    @Test
    void 같은_유저가_다른_idempotencyKey로_재시도해도_두번째_시도의_예약분은_복원된다() {
        int stock = 10;
        Long campaignId = createCampaign(stock);
        Long userId = createUsers(1).get(0);

        IssueResult first = couponIssuanceService.issue(userId, campaignId, "retry-key-1");
        // CouponIssuanceDuplicateGuard가 같은 (campaignId, userId)를 최소 MIN_HOLD(10초)만큼은
        // release() 호출 이후에도 안 지우고 TTL만 줄인 채로 붙잡아둔다(의도된 설계 - 근접
        // 중복요청 폭주 흡수용, CouponIssuanceDuplicateGuard.release() 참고). 이 테스트는 그
        // 가드가 아니라 recoverFromInsertFailure()의 복원 로직 자체를 검증하려는 것이므로,
        // "가드가 이미 완전히 풀린 뒤의 진짜 재시도" 상황을 흉내내기 위해 Redis 키를 직접 지운다
        // (release()를 또 호출해봐야 MIN_HOLD 이내라 여전히 안 지워짐).
        redisTemplate.delete("dup:" + campaignId + ":" + userId);
        IssueResult second = couponIssuanceService.issue(userId, campaignId, "retry-key-2");

        assertThat(first.status()).isEqualTo(IssueResult.Status.SUCCESS);
        assertThat(second.status()).isEqualTo(IssueResult.Status.ALREADY_PROCESSED);
        assertThat(second.couponIssue().getId()).isEqualTo(first.couponIssue().getId());
        // 실제로 발급된 건 1건뿐이어야 하고,
        assertThat(couponIssueRepository.countByCampaignId(campaignId)).isEqualTo(1);
        // 재고는 첫 시도가 쓴 1개만 빠져야 한다 - 두 번째 시도가 확보했다가 실패한 예약분은
        // 반드시 복원돼야 함(9가 아니라 8이면 재고 유실 회귀).
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(stock - 1);
    }

    private Long createCampaign(int stock) {
        Campaign campaign = new Campaign("테스트 캠페인", stock, null);
        campaign.open(LocalDateTime.now(), null);
        campaign = campaignRepository.save(campaign);
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
