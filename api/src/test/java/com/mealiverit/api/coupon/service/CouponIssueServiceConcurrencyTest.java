package com.mealiverit.api.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.coupon.InvalidStateTransitionException;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.entity.CouponStateLog;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponRepository;
import com.mealiverit.entity.coupon.repository.CouponStateLogRepository;
import com.mealiverit.entity.user.MembershipTier;
import com.mealiverit.entity.user.User;
import com.mealiverit.entity.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

// FR-CPS-OO4/005: 같은 쿠폰에 "사용"과 "취소"가 동시에 들어와도 정확히 하나의 최종 상태로 수렴하는지 검증한다.
// CouponIssuanceServiceTest와 동일한 이유로 Mockito가 아닌 실제 MySQL(Testcontainers)을 쓴다.
// - @Version 낙관적 락 충돌과 spring-retry 재시도 경로는 목으로는 재현할 수 없다.

// 상태 머신상 CANCELED -> USED는 금지라 경쟁 순서와 무관하게 항상 CANCELED로 수혐해야 한다.
// (자세한 근거는 클래스 상단 대신 아래 테스트 메서드 주석 참고)
@SpringBootTest
@Testcontainers
public class CouponIssueServiceConcurrencyTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private CouponIssueService couponIssueService;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CouponIssueRepository couponIssueRepository;
    @Autowired
    private CouponStateLogRepository couponStateLogRepository;

    @Test
    void 동일_쿠폰에_사용과_취소가_동시에_들어와도_항상_취소로_수렴() throws InterruptedException {
        Long issueId = createIssueCoupon();

        AtomicReference<Throwable> usedException = new AtomicReference<>();
        AtomicReference<Throwable> canceledException = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new  CountDownLatch(2);
        CountDownLatch start = new  CountDownLatch(1);
        CountDownLatch done = new  CountDownLatch(2);

        pool.submit(() -> {
            ready.countDown();
            await(start);
            try {
                couponIssueService.markUsed(issueId, "used-" + UUID.randomUUID());
            } catch (Throwable e) {
                usedException.set(e);
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            ready.countDown();
            await(start);
            try {
                couponIssueService.markCanceled(issueId, "canceled-" + UUID.randomUUID());
            } catch (Throwable e) {
                canceledException.set(e);
            } finally {
                done.countDown();
            }
        });

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        boolean finishedInTime = done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finishedInTime).as("두 요청이 제한 시간 안에 끝났는지").isTrue();

        //최종 상태: CANCELED가 먼저 커밋되면 USED 재시도가 CANCELED -> USED에 막혀 영구 실패,
        //USED가 먼저 커밋되도 CANCELED 재시도는 USED -> CANCELED가 허용돼 결국 성공 -> 항상 CANCELED
        CouponIssue result = couponIssueRepository.findById(issueId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(CouponStatus.CANCELED);

        assertThat(canceledException.get()).as("취소 요청은 어느 경우든 성공해야 함").isNull();
        if (usedException.get() != null) {
            assertThat(usedException.get()).isInstanceOf(InvalidStateTransitionException.class);
        }

        //로그 체인 검증: 첫 로그는 ISSUED에서 시작, 마지막은 CANCELED로 끝나야 하고 끊기면 안됨
        List<CouponStateLog> logs = couponStateLogRepository.findByCouponIssueIdOrderById(issueId);
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0).getFromStatus()).isEqualTo(CouponStatus.ISSUED);
        assertThat(logs.get(logs.size()-1).getToStatus()).isEqualTo(CouponStatus.CANCELED);
        for (int i = 1; i < logs.size(); i++) {
            assertThat(logs.get(i).getFromStatus()).isEqualTo(logs.get(i-1).getToStatus());
        }
    }

    private  Long createIssueCoupon() {
        Campaign campaign = campaignRepository.save(new Campaign("동시성 테스트 캠페인", 10, null));
        Coupon coupon  = couponRepository.save(new Coupon(campaign.getId(), DiscountType.FIXED, BigDecimal.valueOf(1000), null, null, 24));
        User user = userRepository.save(new User(
                "concurrency-user-" + System.nanoTime(), "테스트 유저", "010-0000-0000",
                "concurrency-" + System.nanoTime() + "@test.com"));

        CouponIssue issue = CouponIssue.issue(campaign.getId(), user.getId(), "idem-" + UUID.randomUUID(), coupon, MembershipTier.PRIVATE, BigDecimal.valueOf(1000));
        return  couponIssueRepository.save(issue).getId();
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
