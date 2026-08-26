package com.mealiverit.api.coupon.controller;

import com.mealiverit.api.common.response.ApiResponse;
import com.mealiverit.api.coupon.dto.CouponIssueResponse;
import com.mealiverit.api.coupon.service.CouponIssueService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class CouponController {

    private final CouponIssueService couponIssueService;

    public CouponController(CouponIssueService couponIssueService) {
        this.couponIssueService = couponIssueService;
    }

    // 결제 페이지 토글용 사용가능한 쿠폰 조회
    @GetMapping("/api/members/me/coupons")
    public ApiResponse<List<CouponIssueResponse>> getIssueCoupons(@RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.success(couponIssueService.getIssuedCoupons(userId));
    }

    // 내 쿠폰함 전체 조회 - 사용가능/사용함/회수됨/만료됨 전부 포함
    @GetMapping("/api/members/me/coupons/all")
    public ApiResponse<List<CouponIssueResponse>> getAllCoupons(@RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.success(couponIssueService.getAllVisibleCoupons(userId));
    }

    //관리자용 쿠폰 강제 회수 (ISSUED→CANCELED)
    @PostMapping("/api/admin/coupons/{issueId}/revoke")
    public ApiResponse<Void> revoke(@PathVariable Long issueId,
                                    @RequestHeader("Idempotency-Key") String requestId) {
        couponIssueService.markCanceled(issueId, requestId);
        return ApiResponse.empty();
    }
}
