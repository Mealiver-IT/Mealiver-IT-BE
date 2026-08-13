package com.mealiverit.api.coupon.notification;

// FR-NOT-001, 05_시스템설계.txt 4절 — 알림 발송은 외부 연동 없이 Mock으로 대체한다.
// 실제 채널(푸시/문자 등) 연동이 생기면 이 인터페이스의 다른 구현체로 교체하면 된다.
public interface NotificationSender {
    void sendCouponIssuedNotification(Long userId, String couponCode);
}
