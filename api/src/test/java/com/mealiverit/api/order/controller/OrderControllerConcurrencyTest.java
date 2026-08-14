package com.mealiverit.api.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.mealiverit.api.coupon.service.CouponIssueService;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.entity.CouponStateLog;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponRepository;
import com.mealiverit.entity.coupon.repository.CouponStateLogRepository;
import com.mealiverit.entity.order.Order;
import com.mealiverit.entity.order.OrderRepository;
import com.mealiverit.entity.user.MembershipTier;
import com.mealiverit.entity.user.User;
import com.mealiverit.entity.user.UserRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// CouponIssueServiceConcurrencyTest는 서비스 빈을 직접 호출해서 @Retryable 재시도가 1번뿐이라
// 문제를 못 잡았다. 실제 PATCH /api/orders/{orderId}/cancel을 MockMvc로 호출해 동일
// Idempotency-Key로 100개 동시요청을 재현해야 재시도가 실제로 반복되는 경로에서
// CouponStateTransitionOperations의 REQUIRES_NEW 전파 회귀를 잡을 수 있다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public class OrderControllerConcurrencyTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "110");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "60000");
    }

    @Autowired
    private MockMvc mockMvc;
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
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private CouponIssueService couponIssueService;

    @Test
    void 동일_idempotencyKey로_주문취소_100개_동시요청해도_전부_성공하고_쿠폰상태전이는_한번만() throws InterruptedException {
        Long couponIssueId = createUsedCoupon();
        Long orderId = createOrder();
        String idempotencyKey = "cancel-" + UUID.randomUUID();
        String body = "{\"couponIssueId\":" + couponIssueId + "}";
        int requesters = 100;

        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(requesters);
        CountDownLatch ready = new CountDownLatch(requesters);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requesters);

        for (int i = 0; i < requesters; i++) {
            pool.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    var result = mockMvc.perform(patch("/api/orders/{orderId}/cancel", orderId)
                                    .header("Idempotency-Key", idempotencyKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();
                    if (result.getResponse().getStatus() != 200) {
                        unexpected.add(new IllegalStateException(result.getResponse().getContentAsString()));
                    }
                } catch (Throwable e) {
                    unexpected.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        boolean finishedInTime = done.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finishedInTime).as("100개 요청이 제한 시간 안에 끝났는지").isTrue();
        assertThat(unexpected).as("동일 Idempotency-Key로 동시 재요청해도 전부 200이어야 함").isEmpty();

        CouponIssue result = couponIssueRepository.findById(couponIssueId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(CouponStatus.ISSUED);

        List<CouponStateLog> logs = couponStateLogRepository.findByCouponIssueIdOrderById(couponIssueId);
        assertThat(logs).hasSize(2); // setup(markUsed) + 이번 취소(markReturnedToIssued) - 재요청은 존재해도 로그 안 늘어남
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Long createUsedCoupon() {
        Campaign campaign = campaignRepository.save(new Campaign("동시성 테스트 캠페인", 10, null));
        Coupon coupon = couponRepository.save(new Coupon(campaign.getId(), DiscountType.FIXED, BigDecimal.valueOf(1000), null, null, 24));
        User user = userRepository.save(new User(
                "order-concurrency-" + System.nanoTime(), "테스트 유저", "010-0000-0000",
                "order-concurrency-" + System.nanoTime() + "@test.com"));

        CouponIssue issue = CouponIssue.issue(campaign.getId(), user.getId(), "idem-" + UUID.randomUUID(),
                coupon, MembershipTier.PRIVATE, BigDecimal.valueOf(1000));
        Long issueId = couponIssueRepository.save(issue).getId();
        couponIssueService.markUsed(issueId, "setup-used-" + UUID.randomUUID());
        return issueId;
    }

    private Long createOrder() {
        Order order = new Order(1L, BigDecimal.valueOf(10000), BigDecimal.valueOf(9000), LocalDateTime.now());
        return orderRepository.save(order).getId();
    }
}
