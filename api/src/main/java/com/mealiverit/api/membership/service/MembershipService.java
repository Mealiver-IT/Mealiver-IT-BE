package com.mealiverit.api.membership.service;

import com.mealiverit.api.batch.MembershipTierBatchJob;
import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.coupon.dto.CouponIssueResponse;
import com.mealiverit.api.membership.dto.MembershipRefreshResponse;
import com.mealiverit.api.membership.dto.MembershipResponse;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.campaign.CampaignType;
import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.user.User;
import com.mealiverit.entity.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MembershipService {

    private final UserRepository userRepository;
    private final MembershipTierBatchJob membershipTierBatchJob;
    private final CouponIssueRepository couponIssueRepository;
    private final CampaignRepository campaignRepository;

    public MembershipService(UserRepository userRepository, MembershipTierBatchJob membershipTierBatchJob, CouponIssueRepository couponIssueRepository, CampaignRepository campaignRepository) {
        this.userRepository = userRepository;
        this.membershipTierBatchJob = membershipTierBatchJob;
        this.couponIssueRepository = couponIssueRepository;
        this.campaignRepository = campaignRepository;
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

    // 계급별 혜택 조회 - MembershipBenefitBatchJob이 발급한 MEMBERSHIP_BENEFIT 캠페인 소속 쿠폼남 걸러서 반환 (선착순으로 받은 쿠폰은 안 섞임)
    // ISSUED(사용 가능)만 노출하는 건 CouponIssueService.getIssuedCoupons()와 동일한 관례
    @Transactional(readOnly = true)
    public List<CouponIssueResponse> getBenefits(Long userId) {
        List<CouponIssue> issues = couponIssueRepository.findByUserIdAndStatusAndCampaignType(userId, CouponStatus.ISSUED, CampaignType.MEMBERSHIP_BENEFIT);
        List<Long> campaignIds = issues.stream().map(CouponIssue::getCampaignId).distinct().toList();
        Map<Long, String> campaignNameById = campaignRepository.findAllById(campaignIds).stream()
                .collect(Collectors.toMap(Campaign::getId, Campaign::getName));

        return issues.stream()
                .map(issue -> CouponIssueResponse.from(issue, campaignNameById.get(issue.getCampaignId())))
                .toList();
    }
}
