package com.mealiverit.api.campaign.dto;

import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.user.MembershipTier;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// 캠페인:쿠폰 1:1(04_아키텍처.txt 1절) — 관리자가 한 번에 캠페인과 쿠폰 정책을 함께 생성한다.
// minMembershipTier가 null이면 전 회원 대상 캠페인.
// scheduledOpenAt을 지정하면 그 시각에 CampaignScheduledOpenBatchJob이 자동으로 오픈 (생략 시 기존처럼 수동 오픈만 가능)
// API 요청 필드명은 의도를 명확히 하려고 scheduledOpenAt이지만, 실제로는 Campaing.openAt 컬럼에 그대로 저장됨 (새 컬럼 없음)
// scheduledCloseAt도 마찬가지로 closeAt 컬럼에 미리 저장해둔다 - 자동으로 마감되는 배치는 아직 없어서
// (지금은 관리자가 "지금 마감" 버튼으로 수동 마감해야 함) 대시보드/상세 화면에 "예정된 마감 시각"을
// 미리 보여주는 용도. open() 호출 시(수동/자동 오픈 둘 다) 이 값을 그대로 넘겨줘야 유지된다.
public record CampaignCreateRequest(
        @NotBlank String name,
        @Positive int totalStock,
        MembershipTier minMembershipTier,
        @NotNull DiscountType discountType,
        @NotNull BigDecimal discountValue,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscountAmount,
        @Min(1) int validHours,
        LocalDateTime scheduledOpenAt,
        LocalDateTime scheduledCloseAt
) {
    // 기존 8-args 호출부 호환용 - scheduledOpenAt/scheduledCloseAt 생략 시 null(예약 없음)
    public CampaignCreateRequest(String name, int totalStock, MembershipTier minMembershipTier,
                                 DiscountType discountType, BigDecimal discountValue, BigDecimal minOrderAmount,
                                 BigDecimal maxDiscountAmount, int validHours) {
        this(name, totalStock, minMembershipTier, discountType, discountValue, minOrderAmount, maxDiscountAmount, validHours, null, null);
    }

    // 9-args(scheduledOpenAt만) 호출부 호환용 - scheduledCloseAt 생략 시 null
    public CampaignCreateRequest(String name, int totalStock, MembershipTier minMembershipTier,
                                 DiscountType discountType, BigDecimal discountValue, BigDecimal minOrderAmount,
                                 BigDecimal maxDiscountAmount, int validHours, LocalDateTime scheduledOpenAt) {
        this(name, totalStock, minMembershipTier, discountType, discountValue, minOrderAmount, maxDiscountAmount, validHours, scheduledOpenAt, null);
    }
}
