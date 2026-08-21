package com.mealiverit.api.coupon.notification;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.entity.campaign.CampaignStockShardRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// DB 선반영 -> 스냅샷 생성 -> Redis 반영(2026-08-19 멘토링 피드백)의 "스냅샷 생성" 단계.
// CouponIssuedNotificationListener와 마찬가지로 발급 트랜잭션이 실제로 커밋된 뒤(AFTER_COMMIT)에만
// 실행되므로, 여기서 다시 읽는 값은 이미 DB에 반영된 최신값이다. 같은 이벤트에 리스너가 하나 더
// 붙는 구조라 CouponIssuedEvent의 "신규 발급 1:1" 발행 규칙에는 영향이 없다.
//
// 2026-08-21 실측(부하테스트 담당자가 sys.innodb_lock_waits로 직접 확인): 이 리스너가 발급
// 성공마다 campaign.remaining_stock에도 UPDATE를 날리고 있었는데, 이게 캠페인 row 하나에
// 락 경합을 다시 만들고 있었다 - 재고 샤딩(#100)으로 없앤 hot row 문제를, 표시용 컬럼 갱신
// 경로에서 그대로 재현한 것. 발급 성공 1건당 1번씩 같은 row를 UPDATE하니, 대량 동시 발급
// 상황에서 InnoDB 행 락 대기가 초당 수만 건까지 치솟는 게 확인됨.
//
// campaign.remaining_stock은 애초에 "표시용" 값이라(진짜 재고는 CampaignStockShard 합계)
// 매 발급마다 실시간일 필요가 없다 - 그래서 이 리스너에서는 DB 쓰기를 빼고 Redis 스냅샷만
// 갱신한다. campaign.remaining_stock 동기화는 CampaignStockSnapshotReconciliationJob(15초
// 주기)에만 맡긴다 - 캠페인당 UPDATE 빈도가 "발급 1건당 1번"에서 "15초에 1번"으로 줄어든다.
@Component
public class CampaignStockSnapshotListener {

    private final CampaignStockShardRepository campaignStockShardRepository;
    private final CampaignStockCache campaignStockCache;

    public CampaignStockSnapshotListener(CampaignStockShardRepository campaignStockShardRepository,
                                          CampaignStockCache campaignStockCache) {
        this.campaignStockShardRepository = campaignStockShardRepository;
        this.campaignStockCache = campaignStockCache;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCouponIssued(CouponIssuedEvent event) {
        int remainingStock = campaignStockShardRepository.sumRemainingStock(event.campaignId());
        campaignStockCache.updateSnapshot(event.campaignId(), remainingStock);
    }
}
