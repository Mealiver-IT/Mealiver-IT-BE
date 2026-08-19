package com.mealiverit.api.coupon.notification;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// DB 선반영 -> 스냅샷 생성 -> Redis 반영(2026-08-19 멘토링 피드백)의 "스냅샷 생성" 단계.
// CouponIssuedNotificationListener와 마찬가지로 발급 트랜잭션이 실제로 커밋된 뒤(AFTER_COMMIT)에만
// 실행되므로, 여기서 다시 읽는 remainingStock은 이미 DB에 반영된 최신값이다. 같은 이벤트에 리스너가
// 하나 더 붙는 구조라 CouponIssuedEvent의 "신규 발급 1:1" 발행 규칙에는 영향이 없다.
@Component
public class CampaignStockSnapshotListener {

    private final CampaignRepository campaignRepository;
    private final CampaignStockCache campaignStockCache;

    public CampaignStockSnapshotListener(CampaignRepository campaignRepository,
                                          CampaignStockCache campaignStockCache) {
        this.campaignRepository = campaignRepository;
        this.campaignStockCache = campaignStockCache;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCouponIssued(CouponIssuedEvent event) {
        campaignRepository.findById(event.campaignId())
                .map(Campaign::getRemainingStock)
                .ifPresent(remainingStock -> campaignStockCache.updateSnapshot(event.campaignId(), remainingStock));
    }
}
