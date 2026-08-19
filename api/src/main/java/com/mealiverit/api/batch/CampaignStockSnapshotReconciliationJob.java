package com.mealiverit.api.batch;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.campaign.CampaignStatus;
import java.util.List;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// CampaignStockCache의 스냅샷은 오직 발급 성공 이벤트(CampaignStockSnapshotListener)로만
// 갱신된다. 그래서 발급 흐름을 거치지 않고 DB remainingStock이 바뀌는 경로(테스트 리셋 SQL,
// 향후 추가될 수 있는 관리자 재고보정/취소복원 등)가 생기면 스냅샷이 그 시점에서 멈춘 채
// 영구히 어긋난다(2026-08-19 실측 - 완판 직후 DB만 리셋하니 스냅샷 0이 그대로 남아 정상
// 재고인데도 사전 필터가 즉시 품절 처리함). DB가 항상 최종 권위이므로(reserve()의 조건부
// UPDATE가 다시 검증) 이 재동기화가 틀린 값을 잠깐 써도 초과발급으로 이어지진 않는다 -
// 반대 방향(정상 재고를 품절로 오판)만 스스로 회복시키는 자가치유 장치다.
// CampaignStockCache.SNAPSHOT_TTL(60s)보다 짧은 주기로 계속 새로 써서 TTL은 이 잡이
// 멈추거나 지연됐을 때만 발동하는 최후 안전장치로 둔다.
@Component
public class CampaignStockSnapshotReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(CampaignStockSnapshotReconciliationJob.class);

    private final CampaignRepository campaignRepository;
    private final CampaignStockCache campaignStockCache;

    public CampaignStockSnapshotReconciliationJob(CampaignRepository campaignRepository,
                                                    CampaignStockCache campaignStockCache) {
        this.campaignRepository = campaignRepository;
        this.campaignStockCache = campaignStockCache;
    }

    @Scheduled(fixedDelay = 15000)
    @SchedulerLock(name = "campaignStockSnapshotReconciliationJob", lockAtLeastFor = "PT10S", lockAtMostFor = "PT1M")
    public void scheduledReconcile() {
        LockAssert.assertLocked();
        reconcile();
    }

    // 테스트에서는 ShedLock 프록시/락 컨텍스트 없이 이 로직만 바로 검증한다
    // (CouponExpirationBatchJob의 run() 분리와 동일한 이유).
    public void reconcile() {
        List<Campaign> openCampaigns = campaignRepository.findByStatus(CampaignStatus.OPEN);
        for (Campaign campaign : openCampaigns) {
            campaignStockCache.updateSnapshot(campaign.getId(), campaign.getRemainingStock());
        }
        log.debug("캠페인 재고 스냅샷 재동기화 완료: {}건", openCampaigns.size());
    }
}
