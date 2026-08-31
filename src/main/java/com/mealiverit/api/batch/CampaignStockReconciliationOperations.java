package com.mealiverit.api.batch;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.campaign.sse.CampaignStockEmitterRegistry;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import com.mealiverit.api.campaign.repository.CampaignStockShardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// CampaignStockSnapshotReconciliationJob에서 분리된 이유: reconcile()이 원래 OPEN 캠페인 전부를
// 순회하는 걸 하나의 @Transactional로 묶고 있었는데(2026-08-26 부하테스트 담당자 리포트로 발견),
// 이러면 캠페인 하나 처리 중 예외가 나면(예: 부하 폭주로 HikariCP 커넥션 획득 실패) 그 사이클에서
// 이미 처리한 다른 캠페인들의 갱신까지 전부 롤백된다 - 15초마다 반복되는 배치인데, 커넥션 풀이
// 계속 포화 상태면 매 사이클 똑같이 전체 롤백되어 테스트 내내 단 하나도 갱신 안 되는 것처럼
// 보일 수 있다. 캠페인 한 건을 별도 REQUIRES_NEW 트랜잭션으로 분리하면 (1) 한 캠페인의 실패가
// 다른 캠페인에 영향을 안 주고, (2) 트랜잭션(=커넥션 보유 시간)이 캠페인 하나 처리하는 짧은
// 구간으로 줄어든다 - CampaignStockShardRepository.decreaseIfAvailable() 등이 이미 같은 이유로
// REQUIRES_NEW를 쓰는 것과 동일한 원칙.
@Component
class CampaignStockReconciliationOperations {

    private static final Logger log = LoggerFactory.getLogger(CampaignStockReconciliationOperations.class);

    private final CampaignRepository campaignRepository;
    private final CampaignStockShardRepository campaignStockShardRepository;
    private final CampaignStockCache campaignStockCache;
    private final CampaignStockEmitterRegistry emitterRegistry;

    CampaignStockReconciliationOperations(CampaignRepository campaignRepository,
                                           CampaignStockShardRepository campaignStockShardRepository,
                                           CampaignStockCache campaignStockCache,
                                           CampaignStockEmitterRegistry emitterRegistry) {
        this.campaignRepository = campaignRepository;
        this.campaignStockShardRepository = campaignStockShardRepository;
        this.campaignStockCache = campaignStockCache;
        this.emitterRegistry = emitterRegistry;
    }

    // 2026-08-26 부하테스트 담당자 후속 리포트("발급 직후엔 맞다가 이후 다시 totalStock으로
    // 원복됨", 재현 타이밍이 15초 배치 주기와 겹침) 진단용 로깅 추가. reconcile()이 샤드 테이블
    // 자체를 쓰는 경로가 없어(순수 SELECT SUM 결과를 복사만 함) 정적 분석만으로는 "왜 SUM이
    // totalStock을 계산하는지" 확인이 안 된다 - 다음 재현 때 이 로그로 실제 계산값과 그 값이
    // 이전 스냅샷보다 늘었는지(재고가 느는 방향은 정상 흐름에서 나올 수 없음 - StockLossRepairJob의
    // "초과 방향은 의심스러움" 원칙과 동일)를 바로 확인할 수 있게 한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void reconcileOne(Long campaignId) {
        int remainingStock = campaignStockShardRepository.sumRemainingStock(campaignId);
        Integer previousSnapshot = campaignStockCache.getSnapshot(campaignId);
        if (previousSnapshot != null && remainingStock > previousSnapshot) {
            log.warn("재고 재동기화: 이전 스냅샷보다 큰 값으로 갱신됨(재고가 느는 방향은 정상 흐름에서 "
                            + "나올 수 없어 의심스러움) - campaignId={}, previousSnapshot={}, "
                            + "샤드합계(computed)={}",
                    campaignId, previousSnapshot, remainingStock);
        }
        log.debug("재고 재동기화: campaignId={}, 샤드합계(computed)={}", campaignId, remainingStock);
        campaignRepository.setRemainingStock(campaignId, remainingStock);
        campaignStockCache.updateSnapshot(campaignId, remainingStock);
        emitterRegistry.broadcast(campaignId, remainingStock);
    }
}
