package com.mealiverit.api.campaign.controller;

import com.mealiverit.api.campaign.dto.CampaignCreateRequest;
import com.mealiverit.api.campaign.dto.CampaignResponse;
import com.mealiverit.api.campaign.dto.CampaignStatusUpdateRequest;
import com.mealiverit.api.campaign.service.CampaignAdminService;
import com.mealiverit.api.common.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 관리자용 캠페인/쿠폰 CRUD API(간단히, 화면 없음). 일정과역할.txt Phase 1.
@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignAdminService campaignAdminService;

    public CampaignController(CampaignAdminService campaignAdminService) {
        this.campaignAdminService = campaignAdminService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CampaignResponse> create(@Valid @RequestBody CampaignCreateRequest request) {
        return ApiResponse.success(campaignAdminService.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<CampaignResponse> getById(@PathVariable Long id) {
        return ApiResponse.success(campaignAdminService.getById(id));
    }

    @GetMapping
    public ApiResponse<List<CampaignResponse>> list() {
        return ApiResponse.success(campaignAdminService.list());
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<CampaignResponse> updateStatus(@PathVariable Long id,
                                                        @Valid @RequestBody CampaignStatusUpdateRequest request) {
        return ApiResponse.success(campaignAdminService.updateStatus(id, request));
    }
}
