package com.mealiverit.api.coupon.service;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.coupon.dto.CouponIssueAdminResponse;
import com.mealiverit.api.coupon.dto.CouponIssuePageResponse;
import com.mealiverit.api.coupon.dto.CouponIssueResponse;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import org.hibernate.AssertionFailure;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    private final CampaignRepository campaignRepository;
    private final CouponStateTransitionOperations transitionOperations;

    public CouponIssueService(CouponIssueRepository couponIssueRepository,
                              CampaignRepository campaignRepository,
                              CouponStateTransitionOperations transitionOperations) {
        this.couponIssueRepository = couponIssueRepository;
        this.campaignRepository = campaignRepository;
        this.transitionOperations = transitionOperations;
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

    private static final int MAX_ADMIN_PAGE_SIZE = 100;

    // 관리자 쿠폰 강제 회수 화면 - 캠페인별 발급 목록 브라우징. 캠페인당 최대 수십만 건까지
    // 있을 수 있어(예: campaign_id=8은 133,339건) size를 강제로 100건 이하로 캡한다.
    @Transactional(readOnly = true)
    public CouponIssuePageResponse listByCampaign(Long campaignId, CouponStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_ADMIN_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "id"));
        var result = status != null
                ? couponIssueRepository.findByCampaignIdAndStatus(campaignId, status, pageable)
                : couponIssueRepository.findByCampaignId(campaignId, pageable);
        return CouponIssuePageResponse.from(result.map(CouponIssueAdminResponse::from));
    }

    // 관리자가 캠페인+유저 조합으로 발급 건 하나를 바로 찾는 용도 (uk_campaign_user 인덱스 활용,
    // 발급 건이 수십만 건인 캠페인에서도 페이지를 넘기지 않고 바로 회수 대상을 특정할 수 있음)
    @Transactional(readOnly = true)
    public CouponIssueAdminResponse findByCampaignAndUser(Long campaignId, Long userId) {
        return couponIssueRepository.findByCampaignIdAndUserId(campaignId, userId)
                .map(CouponIssueAdminResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
    }

    //OrderService가 결제완료(POST /api/orders) 처리 중 내부 호출. 별도 public API 없음
    //requestId: 호출측(OrderService)이 재시도 시에도 동일하게 넘겨야 하는 멱등키
    //실제 트랜잭션은 CouponStateTransitionOperations(별도 빈)에 위임 - 그 클래스 주석 참고.
    //재시도 때마다 그 빈의 프록시를 새로 타야 진짜 새 트랜잭션/세션을 받는다.
    @Retryable(
            retryFor = {ConcurrencyFailureException.class, DataIntegrityViolationException.class, AssertionFailure.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public void markUsed(Long issueId, String requestId) {
        transitionOperations.markUsed(issueId, requestId);
    }

    //OrderService가 주문취소(환불) 처리 중 내부 호출. 별도 public API 없음
    @Retryable(
            retryFor = {ConcurrencyFailureException.class, DataIntegrityViolationException.class, AssertionFailure.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public void markCanceled(Long issueId, String requestId) {
        transitionOperations.markCanceled(issueId, requestId);
    }

    //주문 취소 시 OrderService가 내부 호출. 별도 public API 없음
    @Retryable(
            retryFor = {ConcurrencyFailureException.class, DataIntegrityViolationException.class, AssertionFailure.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public void markReturnedToIssued(Long issueId, String requestId) {
        transitionOperations.markReturnedToIssued(issueId, requestId);
    }
}