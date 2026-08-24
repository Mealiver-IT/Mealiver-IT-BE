package com.mealiverit.api.campaign.controller;

import com.mealiverit.api.campaign.dto.*;
import com.mealiverit.api.campaign.service.CampaignAdminService;
import com.mealiverit.api.campaign.service.CampaignQueueService;
import com.mealiverit.api.campaign.service.CampaignStockStreamService;
import com.mealiverit.api.common.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 관리자용 캠페인/쿠폰 CRUD API(간단히, 화면 없음). 일정과역할.txt Phase 1.
@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignAdminService campaignAdminService;
    private final CampaignQueueService campaignQueueService;
    private final CampaignStockStreamService campaignStockStreamService;

    public CampaignController(CampaignAdminService campaignAdminService, CampaignQueueService campaignQueueService,
                              CampaignStockStreamService campaignStockStreamService) {
        this.campaignAdminService = campaignAdminService;
        this.campaignQueueService = campaignQueueService;
        this.campaignStockStreamService = campaignStockStreamService;
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

    // 선착순 잔여 수량 조회 - 별도 인증 없음(관리자 CRUD와 같은 컨트롤러를 공유하는 기존 패턴 유지)
    @GetMapping("/{campaignId}/stock")
    public ApiResponse<CampaignStockResponse> getStock(@PathVariable Long campaignId) {
        return ApiResponse.success(campaignAdminService.getStock(campaignId));
    }

    // 발급 현황 실시간 반영(소비자용) - 관리자 대시보드와 로직은 동일, URL만 일반 경로
    // 이벤트 상세 화면이 재고 폴링 대신(또는 실패 시 폴백) 쓸 수 있음, 인증 불필요 - /stock과 동일 패턴
    @GetMapping("/{campaignId}/stream")
    public SseEmitter stream(@PathVariable Long campaignId) {
        return campaignStockStreamService.watch(campaignId);
    }

    // 선착순 대기열 상태 조회 - 조회 자체가 대기열 진입도 겸함 (최초 호출 시 등록, 이후엔 조회만)
    // 발급 API를 게이팅하지 않는 안내용 조회, X-User-Id 필요
    @GetMapping("/{campaignId}/queue")
    public ApiResponse<CampaignQueueResponse> getQueueStatus(@PathVariable Long campaignId,
                                                             @RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.success(campaignQueueService.getQueueStatus(campaignId, userId));
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
