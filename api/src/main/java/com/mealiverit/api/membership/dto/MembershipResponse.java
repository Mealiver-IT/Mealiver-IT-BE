package com.mealiverit.api.membership.dto;

import com.mealiverit.entity.user.MembershipTier;
import com.mealiverit.entity.user.User;

import java.time.LocalDateTime;

public record MembershipResponse(
        MembershipTier tier,
        LocalDateTime tierCalculatedAt
) {
    public static MembershipResponse from(User user) {
        return new MembershipResponse(
                user.getMembershipTier(),
                user.getTierCalculatedAt()
        );
    }
}
