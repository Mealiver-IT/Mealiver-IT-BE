package com.mealiverit.api.campaign.sse;

import com.mealiverit.api.campaign.dto.CampaignStatusStreamEvent;
import com.mealiverit.api.campaign.dto.CampaignStockStreamEvent;
import com.mealiverit.entity.campaign.CampaignStatus;
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
// CampaignStockSnapshotListener(발급마다), CampaignStockSnapshotReconciliationJob(15초 주기), CampaignStatusChangeListener(상태 전환 시) 세 곳에서 braodcast()를 호출
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

    // 재고 변화(발급 성공/재동기화) 브로드캐스트
    public void broadcast(Long campaignId, int remainingStock) {
        broadcastEvent(campaignId, "update", new CampaignStockStreamEvent(campaignId, remainingStock));
    }

    // 상태전환(READY->OPEN, OPEN->CLOSED) 브로드캐스트
    public void broadcastStatus(Long campaignId, CampaignStatus status) {
        broadcastEvent(campaignId, "status", new CampaignStatusStreamEvent(campaignId, status));
    }

    private void broadcastEvent(Long campaignId, String eventName, Object payload) {
        List<SseEmitter> emitters = emittersByCampaign.get(campaignId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException e) {
                log.debug("SSE 전송 실패(연결 종료로 추정) - 구독 목록에서 제거 (campaignId={})", campaignId);
                emitters.remove(emitter);
            }
        }
    }
}
