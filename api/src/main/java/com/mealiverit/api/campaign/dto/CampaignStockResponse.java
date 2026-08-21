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
    // totalStock/status는 Redis에 없는 값이라 항상 DB(campaign) 기준. remainingStock만 캐시 우선 -
    // cachedRemainingStock이 null(캐시 미스/Redis 장애)이면 DB 값으로 폴백해 최신 실시간 대시보드 폴링이
    // 발급 트랜잭션과 DB 커넥션을 나눠 쓰지 않도록 한다. 캐시값이 안 맞아도 이 API는 조회 전용이라
    // CouponIssuanceService의 실제 재고 판단에는 영향 없음.
    public static CampaignStockResponse of(Campaign campaign, Integer cachedRemainingStock) {
        int remainingStock = cachedRemainingStock != null ? cachedRemainingStock : campaign.getRemainingStock();
        return new CampaignStockResponse(
                campaign.getId(),
                campaign.getTotalStock(),
                remainingStock,
                campaign.getStatus(),
                remainingStock <= 0
        );
    }
}
