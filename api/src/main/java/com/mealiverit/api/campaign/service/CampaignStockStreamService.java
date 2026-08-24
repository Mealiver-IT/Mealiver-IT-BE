package com.mealiverit.api.campaign.service;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.campaign.dto.CampaignStockResponse;
import com.mealiverit.api.campaign.sse.CampaignStockEmitterRegistry;
import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;

// 발급 현황 실시간 대시보드 - 캠페인 상태(READY/OPEN/CLOSED)와 무관하게 볼 수 있음
// 발급 자격 게이팅과는 무관한 순수 관전용 화면이라 지금 안 열려있거나 이미 끝난 캠페인도 볼 수 있어야 함
// OPEN 상태에서만 실제 갱신 이벤트가 오고(발급, 재동기화가 OPEN에서만 발생), READY/CLOSED는 최초 스냅샷 이후 값이 안 바뀜
@Service
public class CampaignStockStreamService {

    // 데모/발표용이라 넉넉히 30분 - 그 안에 안끝나면 클라이언트가 재연결
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

    private final CampaignRepository campaignRepository;
    private final CampaignStockCache campaignStockCache;
    private final CampaignStockEmitterRegistry emitterRegistry;

    public CampaignStockStreamService(CampaignRepository campaignRepository,
                                      CampaignStockCache campaignStockCache,
                                      CampaignStockEmitterRegistry emitterRegistry) {
        this.campaignRepository = campaignRepository;
        this.campaignStockCache = campaignStockCache;
        this.emitterRegistry = emitterRegistry;
    }

    @Transactional(readOnly = true)
    public SseEmitter watch(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND));

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        emitterRegistry.register(campaignId, emitter);

        CampaignStockResponse snapshot = CampaignStockResponse.of(campaign, campaignStockCache.getSnapshot(campaignId));
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(snapshot));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
