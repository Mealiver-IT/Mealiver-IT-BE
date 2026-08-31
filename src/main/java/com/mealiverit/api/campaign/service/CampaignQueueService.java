package com.mealiverit.api.campaign.service;

import com.mealiverit.api.campaign.cache.CampaignQueueCache;
import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.campaign.dto.CampaignQueueResponse;
import com.mealiverit.api.campaign.dto.CampaignStockResponse;
import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 선착순 대기열 상태 조회 - 실제 발급 API(CouponIssuanceService)를 게이팅하지 않는 안내용 조회
// 대기열 순번과 무관하게 누구나 여전히 POST /api/campaigns/{campaignId}/coupons를 직접 호출할 수 있고, 이 큐는 "내 앞에 몇 명 있는지" 보여주는 것 뿐임
// READY여도 그 사이 다른 유저가 먼저 발급받으면 품절될 수 있음
@Service
public class CampaignQueueService {

    private final CampaignRepository campaignRepository;
    private final CampaignQueueCache campaignQueueCache;
    private final CampaignStockCache campaignStockCache;

    public CampaignQueueService(CampaignRepository campaignRepository, CampaignQueueCache campaignQueueCache, CampaignStockCache campaignStockCache) {
        this.campaignRepository = campaignRepository;
        this.campaignQueueCache = campaignQueueCache;
        this.campaignStockCache = campaignStockCache;
    }

    @Transactional(readOnly = true)
    public CampaignQueueResponse getQueueStatus(Long campaignId, Long userId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND));

        campaignQueueCache.joinIfAbsent(campaignId, userId);
        long totalWaiting = campaignQueueCache.size(campaignId);
        Long rank = campaignQueueCache.rank(campaignId, userId);

        // Redis 장애로 순번 자체가 확인이 안 되면, 안내용으로로 '맨 뒤'로 처리
        long position = rank != null ? rank : totalWaiting + 1;

        // 재고 조회는 GET .../stock과 동일한 소스(Redis 스냅샷 우선, DB 풀백)를 그대로 재사용
        // 재고 샤딩 도입 이후에도 campaign.remainingStock은 거의 실시간으로 동기화 되므로 별도 처리 없이 그대로 사용
        CampaignStockResponse stock = CampaignStockResponse.of(campaign, campaignStockCache.getSnapshot(campaignId));
        boolean ready = position <= stock.remainingStock();

        return CampaignQueueResponse.of(campaignId, position, totalWaiting, ready);
    }
}
