package com.mealiverit.api.coupon.notification;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.campaign.sse.CampaignStockEmitterRegistry;
import com.mealiverit.entity.campaign.CampaignStockShardRepository;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
//
// 2026-08-27 스로틀링 추가: 위 UPDATE 제거로 campaign row 락 경합은 없앴지만, 이 리스너 자체가
// 여전히 발급 성공마다 campaign_stock_shard 50개 행을 SUM()하는 조회를 새로 날리고 있었다 -
// 2만 건 동시요청 부하테스트에서 주 발급 경로(재고 차감+INSERT)와 똑같은 HikariCP 커넥션 풀을
// 두고 초당 수백 건씩 경쟁하는 추가 부하였다(부하테스트 담당자 Prometheus 실측 - 관리자
// 대시보드가 이 캠페인의 SSE를 구독 중일 때 서버 부하가 30초 넘게 안 풀리는 것과 상관관계
// 확인, FE #41도 같은 계열의 SSE 연결 폭주 문제를 다룸). 이 스냅샷은 애초에 "표시용"이라 발급
// 1건마다 정확히 실시간일 필요는 없다 - 캠페인별로 최소 MIN_UPDATE_INTERVAL_MS 간격 안에서는
// 추가 조회를 생략한다(그 사이의 진짜 최신값은 15초 주기 CampaignStockSnapshotReconciliationJob이
// 결국 따라잡아준다). CAS(compareAndSet)로 "이번 갱신을 내가 맡을지"를 판단하므로 별도 락 없이
// 안전하다 - 여러 스레드가 동시에 걸려도 정확히 하나만 통과한다(근사치 스로틀링이라 나머지가
// 살짝 못 통과해도 무해함).
@Component
public class CampaignStockSnapshotListener {

    private static final long MIN_UPDATE_INTERVAL_MS = 200;

    private final CampaignStockShardRepository campaignStockShardRepository;
    private final CampaignStockCache campaignStockCache;
    private final CampaignStockEmitterRegistry emitterRegistry;
    private final ConcurrentHashMap<Long, AtomicLong> lastUpdatedAtMsByCampaign = new ConcurrentHashMap<>();

    public CampaignStockSnapshotListener(CampaignStockShardRepository campaignStockShardRepository,
                                         CampaignStockCache campaignStockCache,
                                         CampaignStockEmitterRegistry emitterRegistry) {
        this.campaignStockShardRepository = campaignStockShardRepository;
        this.campaignStockCache = campaignStockCache;
        this.emitterRegistry = emitterRegistry;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCouponIssued(CouponIssuedEvent event) {
        if (!tryClaimUpdate(event.campaignId())) {
            return;
        }
        int remainingStock = campaignStockShardRepository.sumRemainingStock(event.campaignId());
        campaignStockCache.updateSnapshot(event.campaignId(), remainingStock);
        emitterRegistry.broadcast(event.campaignId(), remainingStock);
    }

    private boolean tryClaimUpdate(Long campaignId) {
        long now = System.currentTimeMillis();
        AtomicLong lastUpdatedAtMs = lastUpdatedAtMsByCampaign.computeIfAbsent(campaignId, id -> new AtomicLong(0));
        long previous = lastUpdatedAtMs.get();
        if (now - previous < MIN_UPDATE_INTERVAL_MS) {
            return false;
        }
        return lastUpdatedAtMs.compareAndSet(previous, now);
    }
}
