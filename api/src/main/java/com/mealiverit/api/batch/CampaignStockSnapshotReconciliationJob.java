package com.mealiverit.api.batch;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.campaign.sse.CampaignStockEmitterRegistry;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.campaign.CampaignStatus;
import com.mealiverit.entity.campaign.CampaignStockShardRepository;
import java.util.List;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// CampaignStockCache의 스냅샷은 오직 발급 성공 이벤트(CampaignStockSnapshotListener)로만
// 갱신된다. 그래서 발급 흐름을 거치지 않고 진짜 재고(CampaignStockShard 합계, 2026-08-20 재고
// 샤딩 도입)가 바뀌는 경로(테스트 리셋 SQL, 향후 추가될 수 있는 관리자 재고보정/취소복원 등)가
// 생기면 스냅샷이 그 시점에서 멈춘 채 영구히 어긋난다(2026-08-19 실측 - 완판 직후 DB만 리셋하니
// 스냅샷 0이 그대로 남아 정상 재고인데도 사전 필터가 즉시 품절 처리함). DB가 항상 최종
// 권위이므로(reserve()의 조건부 UPDATE가 다시 검증) 이 재동기화가 틀린 값을 잠깐 써도
// 초과발급으로 이어지진 않는다 - 반대 방향(정상 재고를 품절로 오판)만 스스로 회복시키는
// 자가치유 장치다. CampaignStockCache.SNAPSHOT_TTL(60s)보다 짧은 주기로 계속 새로 써서 TTL은
// 이 잡이 멈추거나 지연됐을 때만 발동하는 최후 안전장치로 둔다.
@Component
public class CampaignStockSnapshotReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(CampaignStockSnapshotReconciliationJob.class);

    private final CampaignRepository campaignRepository;
    private final CampaignStockShardRepository campaignStockShardRepository;
    private final CampaignStockCache campaignStockCache;
    private final CampaignStockEmitterRegistry emitterRegistry;

    public CampaignStockSnapshotReconciliationJob(CampaignRepository campaignRepository,
                                                  CampaignStockShardRepository campaignStockShardRepository,
                                                  CampaignStockCache campaignStockCache,
                                                  CampaignStockEmitterRegistry emitterRegistry) {
        this.campaignRepository = campaignRepository;
        this.campaignStockShardRepository = campaignStockShardRepository;
        this.campaignStockCache = campaignStockCache;
        this.emitterRegistry = emitterRegistry;
    }

    // 2026-08-21 실측: scheduledReconcile()가 프록시를 거쳐 정상 호출돼도, 그 안에서
    // reconcile()을 this.reconcile()로 그냥 내부 호출하면 Spring AOP self-invocation
    // 문제로 reconcile()의 @Transactional이 프록시를 안 타서 무시된다 - 그 상태로
    // setRemainingStock()(@Modifying UPDATE)을 실행하면 매 주기마다
    // InvalidDataAccessApiUsageException("No active transaction for update or delete
    // query")이 터지고 전체가 롤백돼서 campaign.remaining_stock이 영원히 갱신 안 됐다.
    // ShedLock과 마찬가지로 같은 프록시 체인에 @Transactional을 여기 같이 걸어두면
    // reconcile() 내부 호출도 이미 스레드에 바인딩된 트랜잭션 안에서 실행된다.
    @Transactional
    @Scheduled(fixedDelay = 15000)
    @SchedulerLock(name = "campaignStockSnapshotReconciliationJob", lockAtLeastFor = "PT10S", lockAtMostFor = "PT1M")
    public void scheduledReconcile() {
        LockAssert.assertLocked();
        reconcile();
    }

    // 테스트에서는 ShedLock 프록시/락 컨텍스트 없이 이 로직만 바로 검증한다
    // (CouponExpirationBatchJob의 run() 분리와 동일한 이유). scheduledReconcile()의
    // @Transactional과 별개로, 여기도 남겨둬야 테스트에서 reconcile()을 단독 호출할 때
    // 트랜잭션이 걸린다.
    @Transactional
    public void reconcile() {
        List<Campaign> openCampaigns = campaignRepository.findByStatus(CampaignStatus.OPEN);
        for (Campaign campaign : openCampaigns) {
            // 아직 예약 시도가 한 번도 없어 샤드가 지연 생성되지 않은 캠페인은 건너뛴다 -
            // 여기서 합계(0)를 그대로 쓰면 멀쩡한 신규 OPEN 캠페인의 재고를 0으로 잘못 덮어써서
            // 첫 실제 요청이 오기도 전에 SOLD_OUT으로 오판하게 된다.
            if (!campaignStockShardRepository.existsByCampaignId(campaign.getId())) {
                continue;
            }
            int remainingStock = campaignStockShardRepository.sumRemainingStock(campaign.getId());
            campaignRepository.setRemainingStock(campaign.getId(), remainingStock);
            campaignStockCache.updateSnapshot(campaign.getId(), remainingStock);
            emitterRegistry.broadcast(campaign.getId(), remainingStock);
        }
        log.debug("캠페인 재고 스냅샷 재동기화 완료: {}건", openCampaigns.size());
    }
}
