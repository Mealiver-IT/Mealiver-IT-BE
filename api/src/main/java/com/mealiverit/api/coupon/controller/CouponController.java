package com.mealiverit.api.coupon.controller;

import com.mealiverit.api.common.response.ApiResponse;
import com.mealiverit.api.coupon.dto.CouponIssueAdminResponse;
import com.mealiverit.api.coupon.dto.CouponIssuePageResponse;
import com.mealiverit.api.coupon.dto.CouponIssueResponse;
import com.mealiverit.api.coupon.service.CouponIssueService;
import com.mealiverit.entity.coupon.CouponStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class CouponController {

    private final CouponIssueService couponIssueService;

    public CouponController(CouponIssueService couponIssueService) {
        this.couponIssueService = couponIssueService;
    }

    @GetMapping("/api/members/me/coupons")
    public ApiResponse<List<CouponIssueResponse>> getIssueCoupons(@RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.success(couponIssueService.getIssuedCoupons(userId));
    }

    // 관리자 쿠폰 강제 회수 화면 - 캠페인별 발급 목록 브라우징 (기본 ISSUED만, 최대 100건/페이지)
    @GetMapping("/api/admin/campaigns/{campaignId}/coupon-issues")
    public ApiResponse<CouponIssuePageResponse> listByCampaign(
            @PathVariable Long campaignId,
            @RequestParam(required = false, defaultValue = "ISSUED") CouponStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(couponIssueService.listByCampaign(campaignId, status, page, size));
    }

    // 관리자가 캠페인+유저 조합으로 발급 건 하나를 바로 조회 (uk_campaign_user 인덱스 활용)
    @GetMapping("/api/admin/campaigns/{campaignId}/coupon-issues/by-user/{userId}")
    public ApiResponse<CouponIssueAdminResponse> findByCampaignAndUser(@PathVariable Long campaignId, @PathVariable Long userId) {
        return ApiResponse.success(couponIssueService.findByCampaignAndUser(campaignId, userId));
    }

    //관리자용 쿠폰 강제 회수 (ISSUED→CANCELED)
    @PostMapping("/api/admin/coupons/{issueId}/revoke")
    public ApiResponse<Void> revoke(@PathVariable Long issueId,
                                    @RequestHeader("Idempotency-Key") String requestId) {
        couponIssueService.markCanceled(issueId, requestId);
        return ApiResponse.empty();
    }
}
