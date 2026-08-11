package com.mealiverit.api.campaign.dto;

import com.mealiverit.entity.campaign.CampaignStatus;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

// status=OPEN 전이 시 openAt을 생략하면 현재 시각으로, closeAt은 생략 가능(무기한 오픈).
// status=CLOSED 전이 시 openAt/closeAt은 무시된다.
public record CampaignStatusUpdateRequest(
        @NotNull CampaignStatus status,
        LocalDateTime openAt,
        LocalDateTime closeAt
) {
}
