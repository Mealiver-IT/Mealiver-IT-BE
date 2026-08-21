package com.mealiverit.api.campaign.dto;

// 선착순 대기열 상태 조회(GET /api/campaigns/{campaignId}/queue) 응답
// status는 안내용 판정일 뿐 발급을 보장하지 않음(CampaignQueueService 주석 참고)
// READY여도 그 사이 다른 유저가 먼저 발급받으면 여전히 SOLD_OUT이 날 수 있다.
public record CampaignQueueResponse(
        Long campaignId,
        long position,
        long totalWaiting,
        String status
) {
    public static CampaignQueueResponse of(Long campaignId, long position, long totalWaiting, boolean ready) {
        return new CampaignQueueResponse(campaignId, position, totalWaiting, ready ? "READY" : "WAITING");
    }
}
