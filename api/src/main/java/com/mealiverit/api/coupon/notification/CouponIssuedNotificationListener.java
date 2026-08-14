package com.mealiverit.api.coupon.notification;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.mealiverit.api.notification.NotificationSender;

// 발급 트랜잭션과 알림 발송을 분리한다(05_시스템설계.txt 4절, FR-NOT-001):
// - AFTER_COMMIT: 발급 트랜잭션이 실제로 커밋된 뒤에만 실행 — 재고 소진/uk 제약 위반 등으로
//   트랜잭션이 롤백되면 이벤트 리스너 자체가 호출되지 않는다(Spring이 보장).
// - @Async: 알림 발송(또는 그 실패)이 발급 요청의 응답 시간에 영향을 주지 않도록 별도 스레드에서 실행.
//   MealiverItBeApplication에 @EnableAsync가 있어야 동작한다.
@Component
public class CouponIssuedNotificationListener {

    private final NotificationSender notificationSender;

    public CouponIssuedNotificationListener(NotificationSender notificationSender) {
        this.notificationSender = notificationSender;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCouponIssued(CouponIssuedEvent event) {
        notificationSender.sendCouponIssuedNotification(event.userId(), event.couponCode());
    }
}
