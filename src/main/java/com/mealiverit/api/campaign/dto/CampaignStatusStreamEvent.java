package com.mealiverit.api.campaign.dto;

import com.mealiverit.api.campaign.CampaignStatus;

// SSE 스트림의 상태전환 이벤트 페이로드(event:status)
// 재고 변화와 별개 이벤트로 분리 - 발생 시점과 관심사가 다름
public record CampaignStatusStreamEvent(Long campaignId, CampaignStatus status) {
}
