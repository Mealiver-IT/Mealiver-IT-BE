package com.mealiverit.api.coupon.notification;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 05_시스템설계.txt 4절 — 외부 알림 연동 없이 로그로만 발송을 흉내낸다("발송 완료 여부만 체크",
// 12_멘토답변_확정사항.txt 2-1). "prod" 프로필은 이 프로젝트에 없으므로 사실상 항상 활성화된다.
@Component
@Profile("!prod")
public class MockNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(MockNotificationSender.class);

    @Override
    public void sendCouponIssuedNotification(Long userId, String couponCode) {
        log.info("[MOCK-NOTIFY] userId={} couponCode={} sentAt={}", maskUserId(userId), couponCode, Instant.now());
    }

    // userId 자체는 PII는 아니지만(PiiMasker는 이름/전화/이메일만 다룸), 로그에 남는 식별값을
    // 그대로 노출하지 않는다는 원칙(05_시스템설계.txt 3절)을 알림 로그에도 동일하게 적용한다.
    private String maskUserId(Long userId) {
        String id = String.valueOf(userId);
        if (id.length() <= 2) {
            return "*".repeat(id.length());
        }
        return "*".repeat(id.length() - 2) + id.substring(id.length() - 2);
    }
}
