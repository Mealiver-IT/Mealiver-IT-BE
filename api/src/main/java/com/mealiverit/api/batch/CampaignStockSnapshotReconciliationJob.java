package com.mealiverit.api.batch;

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

// CampaignStockCache의 스냅샷은 오직 발급 성공 이벤트(CampaignStockSnapshotListener)로만
// 갱신된다. 그래서 발급 흐름을 거치지 않고 진짜 재고(CampaignStockShard 합계, 2026-08-20 재고
// 샤딩 도입)가 바뀌는 경로(테스트 리셋 SQL, 향후 추가될 수 있는 관리자 재고보정/취소복원 등)가
// 생기면 스냅샷이 그 시점에서 멈춘 채 영구히 어긋난다(2026-08-19 실측 - 완판 직후 DB만 리셋하니
// 스냅샷 0이 그대로 남아 정상 재고인데도 사전 필터가 즉시 품절 처리함). DB가 항상 최종
// 권위이므로(reserve()의 조건부 UPDATE가 다시 검증) 이 재동기화가 틀린 값을 잠깐 써도
// 초과발급으로 이어지진 않는다 - 반대 방향(정상 재고를 품절로 오판)만 스스로 회복시키는
// 자가치유 장치다. CampaignStockCache.SNAPSHOT_TTL(60s)보다 짧은 주기로 계속 새로 써서 TTL은
// 이 잡이 멈추거나 지연됐을 때만 발동하는 최후 안전장치로 둔다.
//
// 2026-08-26 실측(부하테스트 담당자 리포트): 5,000~20,000 VU 규모 부하테스트에서 remainingStock이
// 테스트 내내 단 한 번도 갱신 안 되는 현상이 재현됐다. 원인으로 유력했던 것: reconcile()이
// OPEN 캠페인 전부를 순회하는 걸 하나의 @Transactional로 묶고 있어서, 극단적 동시성 상황(발급
// 트래픽이 HikariCP 커넥션 풀을 이미 포화시킨 상태)에서 이 배치가 커넥션을 못 받아 예외가 나면
// 그 사이클에서 처리한 다른 모든 캠페인의 갱신까지 통째로 롤백됐다 - 15초마다 반복되는데 풀
// 포화가 테스트 내내 지속되면 매 사이클 똑같이 전체 실패해서 "한 번도 안 바뀜"으로 보인다.
// 캠페인 한 건을 별도 REQUIRES_NEW 트랜잭션(CampaignStockReconciliationOperations.reconcileOne())
// 으로 분리해서, 한 캠페인 처리 실패가 나머지에 전파되지 않게 하고 트랜잭션(=커넥션 보유 시간)도
// 캠페인 하나 처리하는 짧은 구간으로 줄였다.
@Component
public class CampaignStockSnapshotReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(CampaignStockSnapshotReconciliationJob.class);

    private final CampaignRepository campaignRepository;
    private final CampaignStockShardRepository campaignStockShardRepository;
    private final CampaignStockReconciliationOperations reconciliationOperations;

    public CampaignStockSnapshotReconciliationJob(CampaignRepository campaignRepository,
                                                  CampaignStockShardRepository campaignStockShardRepository,
                                                  CampaignStockReconciliationOperations reconciliationOperations) {
        this.campaignRepository = campaignRepository;
        this.campaignStockShardRepository = campaignStockShardRepository;
        this.reconciliationOperations = reconciliationOperations;
    }

    @Scheduled(fixedDelay = 15000)
    @SchedulerLock(name = "campaignStockSnapshotReconciliationJob", lockAtLeastFor = "PT10S", lockAtMostFor = "PT1M")
    public void scheduledReconcile() {
        LockAssert.assertLocked();
        reconcile();
    }

    // 캠페인별 갱신은 reconciliationOperations.reconcileOne()의 REQUIRES_NEW 트랜잭션이 각자
    // 담당하므로, 이 메서드 자체엔 더 이상 @Transactional이 필요 없다(self-invocation으로 인한
    // @Transactional 무시 문제도 자연히 해소됨 - 쓰기 자체가 이 메서드 안에 없기 때문).
    public void reconcile() {
        List<Campaign> openCampaigns = campaignRepository.findByStatus(CampaignStatus.OPEN);
        int reconciled = 0;
        for (Campaign campaign : openCampaigns) {
            // 아직 예약 시도가 한 번도 없어 샤드가 지연 생성되지 않은 캠페인은 건너뛴다 -
            // 여기서 합계(0)를 그대로 쓰면 멀쩡한 신규 OPEN 캠페인의 재고를 0으로 잘못 덮어써서
            // 첫 실제 요청이 오기도 전에 SOLD_OUT으로 오판하게 된다.
            if (!campaignStockShardRepository.existsByCampaignId(campaign.getId())) {
                continue;
            }
            try {
                reconciliationOperations.reconcileOne(campaign.getId());
                reconciled++;
            } catch (Exception e) {
                // 한 캠페인 처리 실패가 나머지 캠페인 처리를 막으면 안 된다 - 실패한 캠페인은
                // 다음 15초 주기에 다시 시도된다.
                log.warn("캠페인 재고 재동기화 실패 - 다음 주기에 재시도됨 (campaignId={})", campaign.getId(), e);
            }
        }
        log.debug("캠페인 재고 스냅샷 재동기화 완료: {}/{}건", reconciled, openCampaigns.size());
    }
}
