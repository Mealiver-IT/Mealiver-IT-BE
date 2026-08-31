package com.mealiverit.api.campaign.dto;

// SSE 스트림의 업데이트 이벤트 페이로드
// 최초 접속 시 보내는 스냅샷과 달리 발급마다 반복 전송되는 이 이벤트는 campaignId+remainingStock만 담음
// CampaignStockSnapshotListener가 매 발급마다 Campaign을 다시 DB에서 안 읽도록 함
public record CampaignStockStreamEvent(Long campaignId, int remainingStock) {
}
