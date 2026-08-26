package com.mealiverit.api.user.controller;

import com.mealiverit.api.common.response.ApiResponse;
import com.mealiverit.api.user.dto.UserResponse;
import com.mealiverit.api.user.service.UserAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping("/api/admin/users")
    public ApiResponse<List<UserResponse>> list() {
        return ApiResponse.success(userAdminService.list());
    }

    // 대시보드 KPI 카드용 - list() 전체 fetch(100만 건 규모에서 수십 초) 없이 총 인원만 가볍게 조회.
    @GetMapping("/api/admin/users/count")
    public ApiResponse<Long> count() {
        return ApiResponse.success(userAdminService.count());
    }
}
