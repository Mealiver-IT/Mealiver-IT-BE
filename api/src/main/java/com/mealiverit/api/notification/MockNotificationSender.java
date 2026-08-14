package com.mealiverit.api.notification;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import com.mealiverit.api.common.config.PiiMasker;
 
// 05_시스템설계.txt 4절, 기능명세서 FR-NOT-001.
// 외부 알림 연동(Slack/이메일 등) 없이, 발급 완료 시 알림이 "발송 시도"되는 흐름만 로그로 남긴다.
// prod 프로파일이 아닐 때만 활성화되며, 나중에 실제 연동 구현체가 생기면 prod에서는 그쪽이 대신 주입된다.
//
// userId는 PiiMasker.maskUserId()로 마스킹해서 로그에 남긴다 — PK 자체는 이름/전화번호/이메일 같은
// 직접적인 PII는 아니지만, 로그에 남는 식별값을 그대로 노출하지 않는다는 원칙(05_시스템설계.txt 3절)을
// 알림 로그에도 동일하게 적용한다.
@Component
@Profile("!prod")
public class MockNotificationSender implements NotificationSender {
 
    private static final Logger log = LoggerFactory.getLogger(MockNotificationSender.class);
 
    @Override
    public void sendCouponIssuedNotification(Long userId, String couponCode) {
        log.info("[MOCK-NOTIFY] userId={} couponCode={} sentAt={}", PiiMasker.maskUserId(userId), couponCode, Instant.now());
    }
}
 