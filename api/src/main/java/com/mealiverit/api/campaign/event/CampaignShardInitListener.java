package com.mealiverit.api.campaign.event;

import com.mealiverit.api.coupon.service.ShardedStockReservationStrategy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 캠페인 생성이 실제로 커밋된 직후 재고 샤드를 바로 만든다(ShardedStockReservationStrategy 상단
// 주석 참고) - 오픈 직후 첫 폭주 트래픽이 샤드 생성을 두고 경쟁할 일이 없어진다.
//
// AFTER_COMMIT인 이유: 커밋 전에 샤드부터 만들면(REQUIRES_NEW라 즉시 커밋됨) 이후 create()가
// 실패해서 캠페인 자체가 롤백될 때 campaign 행 없는 campaign_stock_shard 고아 행이 남는다.
// CampaignStatusChangeListener/CampaignClosedStockCheckListener와 같은 이유로 @Async도 같이
// 붙인다 - 이 리스너가 오래 걸리거나 실패해도 create() API 응답 스레드는 영향받지 않는다
// (설령 이 리스너가 아예 실행 전에 죽어도 reserve()/rollback()의 지연 생성 폴백이 있어 안전).
@Component
public class CampaignShardInitListener {

    private final ShardedStockReservationStrategy shardedStockReservationStrategy;

    public CampaignShardInitListener(ShardedStockReservationStrategy shardedStockReservationStrategy) {
        this.shardedStockReservationStrategy = shardedStockReservationStrategy;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCampaignCreated(CampaignCreatedEvent event) {
        shardedStockReservationStrategy.ensureShardsExist(event.campaignId());
    }
}
