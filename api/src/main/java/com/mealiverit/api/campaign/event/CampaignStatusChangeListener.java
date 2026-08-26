package com.mealiverit.api.campaign.event;

import com.mealiverit.api.campaign.sse.CampaignStockEmitterRegistry;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// CampaignStockSnapshotListener(재고 변환 브로드캐스트)와 짝을 이루는 상태전환 전용 브로드캐스트
// AFTER_COMMIT에서만 실행 -> CampaignAdminService.updateStatus()가 낙관적 락 충돌(@Version)로
// 커밋에 실패할 수 있어서, 실제로 커밋된 상태전환만 구독자에게 알려야 한다.
@Component
public class CampaignStatusChangeListener {

    private final CampaignStockEmitterRegistry emitterRegistry;

    public CampaignStatusChangeListener(CampaignStockEmitterRegistry emitterRegistry) {
        this.emitterRegistry = emitterRegistry;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCampaignStatusChanged(CampaignStatusChangedEvent event) {
        emitterRegistry.broadcastStatus(event.campaignId(), event.status());
    }
}
