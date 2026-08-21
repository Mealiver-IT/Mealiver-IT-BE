package com.mealiverit.api.campaign.controller;

import com.mealiverit.api.campaign.dto.CampaignStatsResponse;
import com.mealiverit.api.campaign.service.CampaignAdminService;
import com.mealiverit.api.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

// 선착순 발급 현황 통계 조회 전용 컨트롤러
// CampaignController(/api/campaigns 하위로 자동 결합)와 달리 URL이 /api/admin/campaign/... 프리픽스를 써야 해 별도의 컨트롤러로 분리
// 서비스 로직은 CampaignAdminService에 그대로 둠
@RestController
public class CampaignStatsController {

    private final CampaignAdminService campaignAdminService;

    public CampaignStatsController(CampaignAdminService campaignAdminService) {
        this.campaignAdminService = campaignAdminService;
    }

    // 선착순 발급 현황 통계 조회 - 관리자 권한, 별도 인증 없음 (다른 admin API들과 동일 패턴)
    @GetMapping("/api/admin/campaigns/{campaignId}/stats")
    public ApiResponse<CampaignStatsResponse> getStats(@PathVariable Long campaignId) {
        return ApiResponse.success(campaignAdminService.getStats(campaignId));
    }
}
