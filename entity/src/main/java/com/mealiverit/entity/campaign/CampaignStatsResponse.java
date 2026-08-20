package com.mealiverit.entity.campaign;

// 선착순 발급 현황 통계 조회(GET /api/admin/campaigns/{campaignId}/stats) 응답
public record CampaignStatsResponse(
        Long campaignId,
        String campaignName,
        int totalStock,
        int remainingStock,
        long issuedCount
) {
    public static CampaignStatsResponse of(Campaign campaign, long issuedCount) {
        return new  CampaignStatsResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getTotalStock(),
                campaign.getRemainingStock(),
                issuedCount
        );
    }
}
