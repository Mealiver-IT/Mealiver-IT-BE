package com.mealiverit.api.coupon.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.campaign.sse.CampaignStockEmitterRegistry;
import com.mealiverit.api.campaign.repository.CampaignStockShardRepository;
import org.junit.jupiter.api.Test;

// 2026-08-27: 발급 성공마다 campaign_stock_shard 50개 행을 SUM()하던 걸, 캠페인별 최소 간격
// (MIN_UPDATE_INTERVAL_MS) 안에서는 생략하도록 스로틀링을 추가했다 - 이 클래스 상단 주석 참고.
// Spring 컨텍스트 없이 순수 단위테스트로 검증한다(이 리스너는 @Async/@TransactionalEventListener
// 배선 자체가 아니라 스로틀링 판단 로직만 검증하면 충분하고, 그 배선은 CouponIssuanceServiceTest
// 같은 통합테스트에서 이미 간접 검증됨).
class CampaignStockSnapshotListenerTest {

    @Test
    void 짧은_간격_안에_연속으로_들어오면_두번째부터는_조회를_생략한다() {
        CampaignStockShardRepository shardRepository = mock(CampaignStockShardRepository.class);
        CampaignStockCache stockCache = mock(CampaignStockCache.class);
        CampaignStockEmitterRegistry emitterRegistry = mock(CampaignStockEmitterRegistry.class);
        when(shardRepository.sumRemainingStock(1L)).thenReturn(42);

        CampaignStockSnapshotListener listener =
                new CampaignStockSnapshotListener(shardRepository, stockCache, emitterRegistry);

        listener.onCouponIssued(new CouponIssuedEvent(100L, "CPN-1", 1L));
        listener.onCouponIssued(new CouponIssuedEvent(101L, "CPN-2", 1L));
        listener.onCouponIssued(new CouponIssuedEvent(102L, "CPN-3", 1L));

        // 스로틀 간격(200ms) 안에 연달아 들어온 두 번째·세 번째 호출은 생략돼야 하므로,
        // 실제 SUM 조회/캐시 갱신/브로드캐스트는 딱 1번만 일어나야 한다.
        verify(shardRepository, times(1)).sumRemainingStock(1L);
        verify(stockCache, times(1)).updateSnapshot(eq(1L), anyInt());
        verify(emitterRegistry, times(1)).broadcast(eq(1L), anyInt());
    }

    @Test
    void 스로틀_간격이_지나면_다시_조회한다() throws InterruptedException {
        CampaignStockShardRepository shardRepository = mock(CampaignStockShardRepository.class);
        CampaignStockCache stockCache = mock(CampaignStockCache.class);
        CampaignStockEmitterRegistry emitterRegistry = mock(CampaignStockEmitterRegistry.class);
        when(shardRepository.sumRemainingStock(1L)).thenReturn(42);

        CampaignStockSnapshotListener listener =
                new CampaignStockSnapshotListener(shardRepository, stockCache, emitterRegistry);

        listener.onCouponIssued(new CouponIssuedEvent(100L, "CPN-1", 1L));
        Thread.sleep(250); // MIN_UPDATE_INTERVAL_MS(200ms)보다 넉넉히 길게 대기
        listener.onCouponIssued(new CouponIssuedEvent(101L, "CPN-2", 1L));

        verify(shardRepository, times(2)).sumRemainingStock(1L);
    }

    @Test
    void 서로_다른_캠페인은_독립적으로_스로틀된다() {
        CampaignStockShardRepository shardRepository = mock(CampaignStockShardRepository.class);
        CampaignStockCache stockCache = mock(CampaignStockCache.class);
        CampaignStockEmitterRegistry emitterRegistry = mock(CampaignStockEmitterRegistry.class);
        when(shardRepository.sumRemainingStock(any())).thenReturn(1);

        CampaignStockSnapshotListener listener =
                new CampaignStockSnapshotListener(shardRepository, stockCache, emitterRegistry);

        listener.onCouponIssued(new CouponIssuedEvent(100L, "CPN-1", 1L));
        listener.onCouponIssued(new CouponIssuedEvent(200L, "CPN-2", 2L));

        // 캠페인 1L의 스로틀 상태가 캠페인 2L의 첫 호출까지 막으면 안 된다 - 둘 다 각자의
        // "첫 호출"이므로 둘 다 통과해야 한다.
        verify(shardRepository, times(1)).sumRemainingStock(1L);
        verify(shardRepository, times(1)).sumRemainingStock(2L);
    }

    @Test
    void 첫_호출은_항상_즉시_반영된다() {
        CampaignStockShardRepository shardRepository = mock(CampaignStockShardRepository.class);
        CampaignStockCache stockCache = mock(CampaignStockCache.class);
        CampaignStockEmitterRegistry emitterRegistry = mock(CampaignStockEmitterRegistry.class);
        when(shardRepository.sumRemainingStock(1L)).thenReturn(7);

        CampaignStockSnapshotListener listener =
                new CampaignStockSnapshotListener(shardRepository, stockCache, emitterRegistry);

        listener.onCouponIssued(new CouponIssuedEvent(100L, "CPN-1", 1L));

        verify(stockCache, times(1)).updateSnapshot(1L, 7);
        verify(emitterRegistry, times(1)).broadcast(1L, 7);
    }
}
