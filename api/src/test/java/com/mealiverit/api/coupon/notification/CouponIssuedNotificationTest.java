package com.mealiverit.api.coupon.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealiverit.api.coupon.service.CouponIssuanceService;
import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.notification.NotificationSender;
import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import com.mealiverit.api.coupon.DiscountType;
import com.mealiverit.api.coupon.entity.Coupon;
import com.mealiverit.api.coupon.repository.CouponRepository;
import com.mealiverit.api.user.entity.User;
import com.mealiverit.api.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// 05_시스템설계.txt 4절 / FR-NOT-001: 발급 트랜잭션과 알림 발송이 실제로 분리되어 있는지 검증.
// 클래스 레벨에 @Transactional을 절대 붙이면 안 된다 — 붙이면 Spring 테스트가 각 테스트 종료 시
// 롤백해버려서 실제 커밋이 한 번도 안 일어나고, AFTER_COMMIT 리스너가 영원히 안 불린다.
@SpringBootTest
class CouponIssuedNotificationTest {

    @TestConfiguration
    static class RecordingNotificationSenderConfig {
        @Bean
        @Primary
        RecordingNotificationSender recordingNotificationSender() {
            return new RecordingNotificationSender();
        }
    }

    static class RecordingNotificationSender implements NotificationSender {
        final CopyOnWriteArrayList<Long> notifiedUserIds = new CopyOnWriteArrayList<>();
        volatile CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void sendCouponIssuedNotification(Long userId, String couponCode) {
            notifiedUserIds.add(userId);
            latch.countDown();
        }
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
    private RecordingNotificationSender recordingNotificationSender;

    @BeforeEach
    void resetRecorder() {
        recordingNotificationSender.latch = new CountDownLatch(1);
        recordingNotificationSender.notifiedUserIds.clear();
    }

    @Test
    void 발급_성공시_커밋_이후_비동기로_알림이_발송된다() throws InterruptedException {
        Long campaignId = createOpenCampaign(10);
        Long userId = createUser();

        couponIssuanceService.issue(userId, campaignId, "notify-key-success");

        boolean notified = recordingNotificationSender.latch.await(3, TimeUnit.SECONDS);

        assertThat(notified).as("발급 트랜잭션 커밋 후 알림이 발송됐는지").isTrue();
        assertThat(recordingNotificationSender.notifiedUserIds).containsExactly(userId);
    }

    @Test
    void 품절로_발급이_실패하면_알림이_발송되지_않는다() throws InterruptedException {
        Long campaignId = createOpenCampaign(0);
        Long userId = createUser();

        try {
            couponIssuanceService.issue(userId, campaignId, "notify-key-soldout");
        } catch (BusinessException ignored) {
            // SOLD_OUT 예상된 실패
        }

        boolean notified = recordingNotificationSender.latch.await(1, TimeUnit.SECONDS);
        assertThat(notified).as("재고 확보 전에 실패했으니 알림이 없어야 함").isFalse();
    }

    private Long createOpenCampaign(int stock) {
        Campaign campaign = new Campaign("알림 테스트 캠페인", stock, null);
        campaign.open(LocalDateTime.now(), null);
        campaign = campaignRepository.save(campaign);
        couponRepository.save(new Coupon(campaign.getId(), DiscountType.FIXED,
                BigDecimal.valueOf(1000), null, null, 24));
        return campaign.getId();
    }

    private Long createUser() {
        String suffix = System.nanoTime() + "";
        User user = userRepository.save(new User(
                "user-" + suffix, "테스트유저", "010-0000-0000", "user-" + suffix + "@test.com"));
        return user.getId();
    }
}
