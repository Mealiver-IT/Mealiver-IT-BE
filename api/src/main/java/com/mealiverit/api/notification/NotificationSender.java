package com.mealiverit.api.notification;

//05_시스템설계.txt 4절, 기능명세서 FR-NOT-001.
//발급 트랜잭션과 알림 발송을 분리하기 위한 추상화. 실제 구현체(Slack/이메일 등)는
//prod 프로파일에서 별도로 주입되고, 그 전까지는 MockNotificationSender가 대신한다.
public interface NotificationSender {

 void sendCouponIssuedNotification(Long userId, String couponCode);
}
