package com.mealiverit.api.coupon.notification;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.campaign.CampaignStockShardRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// DB 선반영 -> 스냅샷 생성 -> Redis 반영(2026-08-19 멘토링 피드백)의 "스냅샷 생성" 단계.
// CouponIssuedNotificationListener와 마찬가지로 발급 트랜잭션이 실제로 커밋된 뒤(AFTER_COMMIT)에만
// 실행되므로, 여기서 다시 읽는 값은 이미 DB에 반영된 최신값이다. 같은 이벤트에 리스너가 하나 더
// 붙는 구조라 CouponIssuedEvent의 "신규 발급 1:1" 발행 규칙에는 영향이 없다.
//
// 2026-08-20 재고 샤딩 도입: 진짜 재고는 CampaignStockShard 합계이고, campaign.remaining_stock은
// 더 이상 reserve()가 직접 갱신하지 않는다(ShardedStockReservationStrategy 참고). 그래서 이
// 리스너가 샤드 합계를 Redis 스냅샷뿐 아니라 campaign.remaining_stock에도 복사해줘야, 관리자
// CRUD 응답/검증쿼리(b)처럼 그 컬럼을 그대로 읽는 기존 코드가 계속 "거의 실시간" 값을 본다.
@Component
public class CampaignStockSnapshotListener {

    private final CampaignRepository campaignRepository;
    private final CampaignStockShardRepository campaignStockShardRepository;
    private final CampaignStockCache campaignStockCache;

    public CampaignStockSnapshotListener(CampaignRepository campaignRepository,
                                          CampaignStockShardRepository campaignStockShardRepository,
                                          CampaignStockCache campaignStockCache) {
        this.campaignRepository = campaignRepository;
        this.campaignStockShardRepository = campaignStockShardRepository;
        this.campaignStockCache = campaignStockCache;
    }

    @Async
    // AFTER_COMMIT은 원래 트랜잭션이 이미 끝난 뒤라 그 트랜잭션에 "참여"할 수 없다 - Spring이
    // @TransactionalEventListener + 기본 전파(REQUIRED) 조합 자체를 기동 시점에 막는다.
    // REQUIRES_NEW로 완전히 새 트랜잭션을 열어야 setRemainingStock()의 @Modifying 쿼리가 동작한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCouponIssued(CouponIssuedEvent event) {
        int remainingStock = campaignStockShardRepository.sumRemainingStock(event.campaignId());
        campaignRepository.setRemainingStock(event.campaignId(), remainingStock);
        campaignStockCache.updateSnapshot(event.campaignId(), remainingStock);
    }
}
