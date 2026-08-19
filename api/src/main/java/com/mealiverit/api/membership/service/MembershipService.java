package com.mealiverit.api.membership.service;

import com.mealiverit.api.batch.MembershipTierBatchJob;
import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.membership.dto.MembershipRefreshResponse;
import com.mealiverit.api.membership.dto.MembershipResponse;
import com.mealiverit.entity.user.User;
import com.mealiverit.entity.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

@Service
public class MembershipService {

    private final UserRepository userRepository;
    private final MembershipTierBatchJob membershipTierBatchJob;

    public MembershipService(UserRepository userRepository, MembershipTierBatchJob membershipTierBatchJob) {
        this.userRepository = userRepository;
        this.membershipTierBatchJob = membershipTierBatchJob;
    }

    // 내 멤버십 계급 조회 - users.membershipTier/tierCalculatedAt은 MembershipTierBatchJob이 매월 1일 갱신하는 값을 그대로 조회만 함
    // 이 API 자체는 아무것도 계산하지 않음
    @Transactional(readOnly = true)
    public MembershipResponse getMembership(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return MembershipResponse.from(user);
    }

    // 계급 갱신 수동 실행 - MembershipTierBatchJob.runMonthly()가 매월 1일 자동으로 하는 것과 동일하게 "전월" 윈도우로 즉시 1회 실행
    // 배치 자체가 JdbcTemplate 직접 호출이라 여기서 별도 @Transactional은 안 씀(배치의 원래 실행 방식 그대로 유지)
    public MembershipRefreshResponse refreshTiers() {
        MembershipTierBatchJob.Result result = membershipTierBatchJob.run(YearMonth.now().minusMonths(1));
        return MembershipRefreshResponse.from(result);
    }
}
