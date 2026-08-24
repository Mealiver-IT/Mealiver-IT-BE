package com.mealiverit.api.campaign.sse;

import com.mealiverit.api.campaign.dto.CampaignStockStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// 캠페인별 SSE 구독자(이 화면을 보고 있는 사람) 목록 관리
// CampaignStockSnapshotListener(발급마다)와 CampaignStockSnapshotReconciliationJob(15초 주기) 양쪽에서 braodcast()를 호출
// CopyOnWriteArrayList라 순회 중 제거(연결 종료 등)해도 ConcurrentModificationException 없음
@Component
public class CampaignStockEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(CampaignStockEmitterRegistry.class);

    private final Map<Long, List<SseEmitter>> emittersByCampaign = new ConcurrentHashMap<>();

    public void register(Long campaignId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByCampaign.computeIfAbsent(campaignId, id -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError((e) -> emitters.remove(emitter));
    }

    public void broadcast(Long campaignId, int remainingStock) {
        List<SseEmitter> emitters = emittersByCampaign.get(campaignId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        CampaignStockStreamEvent payload = new CampaignStockStreamEvent(campaignId, remainingStock);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("update").data(payload));
            } catch (IOException e) {
                log.debug("SSE 전송 실패(연결 종료로 추정) - 구독 목록에서 제거 (campaignId={})", campaignId);
                emitters.remove(emitter);
            }
        }
    }
}
