package com.mealiverit.api.coupon.service;

import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import org.springframework.stereotype.Component;

// V2 — DB 비관적 락 (03_버전사다리_실험설계.txt 4절). Phase 1 MVP 기본 전략.
// campaign row를 SELECT ... FOR UPDATE로 잠그고 재고를 확인/차감하므로, 같은 트랜잭션 안에서
// 정확성은 보장되지만 hot row 경합이 발생한다(20,000 요청이 캠페인 로우 하나에 줄을 섬) — 의도된 특성.
@Component
public class PessimisticLockStockReservationStrategy implements StockReservationStrategy {

    private final CampaignRepository campaignRepository;

    public PessimisticLockStockReservationStrategy(CampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    @Override
    public boolean reserve(Long campaignId) {
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("campaign not found: " + campaignId));
        if (campaign.getRemainingStock() <= 0) {
            return false;
        }
        campaign.decreaseStock();
        return true;
    }

    @Override
    public void rollback(Long campaignId) {
        // reserve()와 이 rollback() 호출은 같은 트랜잭션 안에서 일어나므로, 아직 커밋 전인
        // Campaign.remainingStock을 그대로 되돌리면 된다(락도 계속 보유 중이라 별도 재조회 불필요).
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("campaign not found: " + campaignId));
        campaign.restoreStock();
    }
}
