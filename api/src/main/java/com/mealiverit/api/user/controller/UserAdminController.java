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
}
