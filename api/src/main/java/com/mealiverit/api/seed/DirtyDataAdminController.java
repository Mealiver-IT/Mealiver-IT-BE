package com.mealiverit.api.seed;

import com.mealiverit.api.common.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DirtyDataAdminController {

    private final DirtyDataAdminService dirtyDataAdminService;

    public DirtyDataAdminController(DirtyDataAdminService dirtyDataAdminService) {
        this.dirtyDataAdminService = dirtyDataAdminService;
    }

    @PostMapping("/api/admin/dirty-data/seed")
    public ApiResponse<Void> seed() {
        dirtyDataAdminService.seed();
        return ApiResponse.empty();
    }

    @PostMapping("/api/admin/dirty-data/cleanup")
    public ApiResponse<Void> cleanup() {
        dirtyDataAdminService.cleanup();
        return ApiResponse.empty();
    }
}
