package com.mealiverit.api.batch;

import com.mealiverit.api.campaign.event.CampaignStatusChangedEvent;
import com.mealiverit.entity.campaign.CampaignStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 캠페인이 CLOSED로 전환된 직후 StockLossRepairJob.checkOnClose()로 재고 정합성을 마지막으로
// 한 번 검증한다 - 이 시점 이후로는 CampaignRepository.findStockMismatches()가 CLOSED 캠페인을
// 주기 검증(60초) 대상에서 제외하므로(StockLossRepairJob 상단 주석 참고), 여기가 그 캠페인에
// 대해 이상 여부를 알 수 있는 사실상 마지막 기회다.
//
// CampaignStatusChangeListener(SSE 브로드캐스트)와 같은 CampaignStatusChangedEvent를 구독하지만
// 책임이 다르다(하나는 실시간 화면 반영, 하나는 재고 정합성 검증) - 그래서 한 리스너에 몰아넣지
// 않고 분리했다.
//
// AFTER_COMMIT: CampaignAdminService.updateStatus()가 낙관적 락(@Version) 충돌로 롤백될 수
// 있어서, 실제로 커밋된 전환에 대해서만 검증해야 한다(같은 이유로 CampaignStatusChangeListener도
// AFTER_COMMIT을 쓴다).
@Component
public class CampaignClosedStockCheckListener {

    private final StockLossRepairJob stockLossRepairJob;

    public CampaignClosedStockCheckListener(StockLossRepairJob stockLossRepairJob) {
        this.stockLossRepairJob = stockLossRepairJob;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCampaignStatusChanged(CampaignStatusChangedEvent event) {
        if (event.status() == CampaignStatus.CLOSED) {
            stockLossRepairJob.checkOnClose(event.campaignId());
        }
    }
}
