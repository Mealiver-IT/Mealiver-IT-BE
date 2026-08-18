package com.mealiverit.api.membership.controller;

import com.mealiverit.api.common.response.ApiResponse;
import com.mealiverit.api.membership.dto.MembershipResponse;
import com.mealiverit.api.membership.service.MembershipService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MembershipController {

    private final MembershipService membershipService;

    public MembershipController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    // 내 멤버십 계급 조회 - X-User-Id 헤더로 본인 계급만 조회
    @GetMapping("/api/members/me/membership")
    public ApiResponse<MembershipResponse> getMembership(@RequestHeader("X-User-Id") Long userId) {
        return ApiResponse.success(membershipService.getMembership(userId));
    }
}
