package com.mealiverit.api.coupon.service;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.coupon.dto.CouponIssueResponse;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.entity.CouponStateLog;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponStateLogRepository;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CouponIssueService {

    private final CouponIssueRepository couponIssueRepository;
    private final CouponStateLogRepository couponStateLogRepository;
    private final CampaignRepository campaignRepository;

    public CouponIssueService(CouponIssueRepository couponIssueRepository,
                              CouponStateLogRepository couponStateLogRepository,
                              CampaignRepository campaignRepository) {
        this.couponIssueRepository = couponIssueRepository;
        this.couponStateLogRepository = couponStateLogRepository;
        this.campaignRepository = campaignRepository;
    }

    //결제 페이지 토글 UI용 - 사용 가능(ISSUED)한 쿠폰만 반환
    public List<CouponIssueResponse> getIssuedCoupons(Long userId) {
        List<CouponIssue> issues = couponIssueRepository.findByUserIdAndStatus(userId, CouponStatus.ISSUED);
        List<Long> campaignIds = issues.stream().map(CouponIssue::getCampaignId).distinct().toList();
        Map<Long, String> campaignNameById = campaignRepository.findAllById(campaignIds).stream()
                .collect(Collectors.toMap(Campaign::getId, Campaign::getName));

        return issues.stream()
                .map(issue -> CouponIssueResponse.from(issue, campaignNameById.get(issue.getCampaignId())))
                .toList();
    }

    //OrderService가 결제완료(POST /api/orders) 처리 중 내부 호출. 별도 public API 없음
    //requestId: 호출측(OrderService)이 재시도 시에도 동일하게 넘겨야 하는 멱등키
    //DataIntegrityViolationException(uk_state_log_reqeust 경합) 재시도 대상 추가
    //동시 요청으로 인한 @Version 충돌/멱등키 경합 시 지수 백오프로 최대 3회 재시도
    @Retryable(
            retryFor = {ConcurrencyFailureException.class, DataIntegrityViolationException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional
    public void markUsed(Long issueId, String requestId) {
        if (couponStateLogRepository.existsByRequestId(requestId)) {
            return; //이미 처리된 요청 - 재시도로 들어와도 아무 것도 안하고 조용히 반환
        }
        CouponIssue issue = findIssueOrThrow(issueId);
        CouponStatus before = issue.getStatus();
        issue.markUsed();
        couponStateLogRepository.save(new CouponStateLog(issueId, before, issue.getStatus(), requestId));
    }

    //OrderService가 주문취소(환불) 처리 중 내부 호출. 별도 public API 없음
    //requestId: 호출측(OrderService)이 재시도 시에도 동일하게 넘겨야 하는 멱등키
    //동시 요청으로 인한 @Version 충돌 시 지수 백오프로 최대 3회 재시도
    @Retryable(
            retryFor = {ConcurrencyFailureException.class, DataIntegrityViolationException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional
    public void markCanceled(Long issueId, String requestId) {
        if (couponStateLogRepository.existsByRequestId(requestId)) {
            return; //이미 처리된 요청 - 재시도로 들어와도 아무 것도 안하고 조용히 반환
        }
        CouponIssue issue = findIssueOrThrow(issueId);
        CouponStatus before = issue.getStatus();
        issue.markCanceled();
        couponStateLogRepository.save(new CouponStateLog(issueId, before, issue.getStatus(), requestId));
    }

    //주문 취소 시 OrderService가 내부 호출. 별도 public API 없음
    //requestId: 호출측(OrderService)이 재시도 시에도 동일하게 넘겨야 한느 멱등키
    //동시 요청으로 인한 @Version 충돌 시 지수 백오프로 최대 3회 재시도
    @Retryable(
            retryFor = {ConcurrencyFailureException.class, DataIntegrityViolationException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    @Transactional
    public void markReturnedToIssued(Long issueId, String requestId) {
        if (couponStateLogRepository.existsByRequestId(requestId)) {
            return; //이미 처리된 요청 - 재시도로 들어와도 아무것도 안하고 조용히 반환
        }
        CouponIssue issue = findIssueOrThrow(issueId);
        CouponStatus before = issue.getStatus();
        issue.markReturnedToIssued();
        couponStateLogRepository.save(new CouponStateLog(issueId, before, issue.getStatus(), requestId));
    }

    private CouponIssue findIssueOrThrow(Long issueId) {
        return couponIssueRepository.findById(issueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
    }
}
