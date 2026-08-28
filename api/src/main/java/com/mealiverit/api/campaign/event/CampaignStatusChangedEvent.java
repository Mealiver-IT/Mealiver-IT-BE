package com.mealiverit.api.campaign.event;

import com.mealiverit.api.campaign.CampaignStatus;

// 캠페인 상태 수동 전환(PATCH /api/campaigns/{id}/status) 성공 시 발행
// SSE 스트림 구독자에게 상태전환(READY->OPEN, OPEN->CLOSED)을 실시간으로 알리기 위한 용도
public record CampaignStatusChangedEvent(Long campaignId, CampaignStatus status) {
}
