package com.mealiverit.api.coupon.notification;

// 발급 성공(신규 INSERT 성공) 시점에만 발행된다 — idempotency 재응답이나 uk_campaign_user 충돌
// 복구(recoverFromConflict) 경로에서는 발행하지 않는다. "발급 성공 건수 == 알림 시도 건수"를
// 검증 지표로 쓰려면(05_시스템설계.txt 4절) 이 이벤트가 신규 발급에만 1:1로 대응해야 한다.
// campaignId는 CampaignStockSnapshotListener가 재고 스냅샷을 Redis에 반영할 때 사용한다(리스너가
// 늘어도 발행은 한 번뿐이라 위 1:1 불변식은 그대로 유지된다).
public record CouponIssuedEvent(Long userId, String couponCode, Long campaignId) {
}
