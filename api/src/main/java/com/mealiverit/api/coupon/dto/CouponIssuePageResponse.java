package com.mealiverit.api.coupon.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record CouponIssuePageResponse(
        List<CouponIssueAdminResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static CouponIssuePageResponse from(Page<CouponIssueAdminResponse> page) {
        return new CouponIssuePageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
