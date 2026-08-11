package com.mealiverit.api.campaign.dto;

import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignStatus;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.user.MembershipTier;
import java.time.LocalDateTime;

public record CampaignResponse(
        Long id,
        String name,
        int totalStock,
        int remainingStock,
        CampaignStatus status,
        MembershipTier minMembershipTier,
        LocalDateTime openAt,
        LocalDateTime closeAt,
        CouponResponse coupon
) {
    public static CampaignResponse of(Campaign campaign, Coupon coupon) {
        return new CampaignResponse(campaign.getId(), campaign.getName(), campaign.getTotalStock(),
                campaign.getRemainingStock(), campaign.getStatus(), campaign.getMinMembershipTier(),
                campaign.getOpenAt(), campaign.getCloseAt(), coupon == null ? null : CouponResponse.of(coupon));
    }
}
