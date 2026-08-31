package com.mealiverit.api.coupon.controller;

import com.mealiverit.api.common.response.ApiResponse;
import com.mealiverit.api.coupon.dto.CouponIssueResponse;
import com.mealiverit.api.coupon.service.CouponIssuanceService;
import com.mealiverit.api.coupon.service.IssueResult;
import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// 선착순 쿠폰 발급(claim). 04_아키텍처.txt 5.1절 계약: Idempotency-Key 헤더 필수, 재요청도 동일 응답.
// 인증 시스템이 없는 프로젝트라 CouponController(dev)와 동일하게 X-User-Id 헤더로 사용자를 식별한다.
// 관리자용 CampaignController(/api/campaigns CRUD)와는 별개 컨트롤러로 둔다 — 소비자용 발급과
// 관리자용 캠페인 관리는 책임이 다르다(경로만 /api/campaigns를 공유).
@RestController
public class CouponClaimController {

    private final CouponIssuanceService couponIssuanceService;
    private final CampaignRepository campaignRepository;

    public CouponClaimController(CouponIssuanceService couponIssuanceService, CampaignRepository campaignRepository) {
        this.couponIssuanceService = couponIssuanceService;
        this.campaignRepository = campaignRepository;
    }

    @PostMapping("/api/campaigns/{campaignId}/coupons")
    public ResponseEntity<ApiResponse<CouponIssueResponse>> claim(
            @PathVariable Long campaignId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        IssueResult result = couponIssuanceService.issue(userId, campaignId, idempotencyKey);
        HttpStatus status = result.status() == IssueResult.Status.SUCCESS ? HttpStatus.CREATED : HttpStatus.OK;
        String campaignName = campaignRepository.findById(campaignId).map(Campaign::getName).orElse(null);
        return ResponseEntity.status(status)
                .body(ApiResponse.success(CouponIssueResponse.from(result.couponIssue(), campaignName)));
    }
}
