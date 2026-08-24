package com.mealiverit.api.user.dto;

import com.mealiverit.api.common.config.PiiMasker;
import com.mealiverit.entity.user.MembershipTier;
import com.mealiverit.entity.user.User;

import java.time.LocalDateTime;

// 유저 목록 조회(GET /api/admin/users) 응답 - users 테이블 전체 컬럼 반환
public record UserResponse(
        Long id,
        String loginId,
        String name,
        String phone,
        String email,
        MembershipTier membershipTier,
        LocalDateTime tierCalculatedAt,
        LocalDateTime createdAt
) {
    // Jackson 버전(편재 프로젝트는 Jackson3 기반이라 PiiMaskingSerializers의 @JsonSerialize가 적용 안 될 위험이 있음)과
    // 무관하게 항상 마스킹이 적용되도록 직렬화 시점이 아니라 여기서 이미 마스킹된 문자열을 DTO에 담는다.
    public static UserResponse of(User user) {
        return new UserResponse(
                user.getId(),
                user.getLoginId(),
                PiiMasker.maskName(user.getName()),
                PiiMasker.maskPhone(user.getPhone()),
                PiiMasker.maskEmail(user.getEmail()),
                user.getMembershipTier(),
                user.getTierCalculatedAt(),
                user.getCreatedAt()
        );
    }
}
