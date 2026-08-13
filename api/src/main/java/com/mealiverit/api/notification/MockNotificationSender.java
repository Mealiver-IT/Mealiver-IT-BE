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
// userId는 마스킹하지 않고 그대로 로그에 남긴다 — PK 자체는 다른 테이블과 조인하지 않는 한
// 직접적인 개인식별정보가 아니라는 팀 판단(PiiMasker는 이름/전화번호/이메일처럼 값 자체가
// PII인 필드만 마스킹 대상으로 함). 마스킹이 필요한 필드가 로그에 섞이지 않도록 주의할 것.
@Component
@Profile("!prod")
public class MockNotificationSender implements NotificationSender {
 
    private static final Logger log = LoggerFactory.getLogger(MockNotificationSender.class);
 
    @Override
    public void sendCouponIssuedNotification(Long userId, String couponCode) {
        log.info("[MOCK-NOTIFY] userId={} couponCode={} sentAt={}", PiiMasker.maskUserId(userId), couponCode, Instant.now());
    }
}
 