package com.mealiverit.api.campaign.dto;

import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignStatus;

// 선착순 잔여 수량 조회(GET /api/campaigns/{campaignId}/stock) 응답.
// CampaignStatus에는 "조기마감"이라는 별도 상태가 없고 재고 0 도달 시 자동으로 CLOSED 전환하는 로직도 없어서 soldOut을 remainingStock 기준 파생 필드로 따로 둠
// status는 DB에 실제로 저장된 값 그대로 보여주고 거짓으로 조작하지 않음
public record CampaignStockResponse(
        Long campaignId,
        int totalStock,
        int remainingStock,
        CampaignStatus status,
        boolean soldOut
) {
    public static CampaignStockResponse from(Campaign campaign) {
        return new CampaignStockResponse(
                campaign.getId(),
                campaign.getTotalStock(),
                campaign.getRemainingStock(),
                campaign.getStatus(),
                campaign.getRemainingStock() <= 0
        );
    }
}
