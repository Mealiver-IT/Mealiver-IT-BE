package com.mealiverit.api.membership.service;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.membership.dto.MembershipResponse;
import com.mealiverit.entity.user.User;
import com.mealiverit.entity.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipService {

    private final UserRepository userRepository;

    public MembershipService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 내 멤버십 계급 조회 - users.membershipTier/tierCalculatedAt은 MembershipTierBatchJob이 매월 1일 갱신하는 값을 그대로 조회만 함
    // 이 API 자체는 아무것도 계산하지 않음
    @Transactional(readOnly = true)
    public MembershipResponse getMembership(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return MembershipResponse.from(user);
    }
}
