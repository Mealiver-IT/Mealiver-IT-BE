package com.mealiverit.api.coupon.service;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.entity.CouponStateLog;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponStateLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// CouponIssueService에서 분리된 이유 + REQUIRES_NEW인 이유: @Retryable 재시도가 REQUIRED
// 전파로 이전 시도의 트랜잭션에 편승하면, 그 트랜잭션이 flush 실패로 세션이 깨진 채였을 때
// 재시도도 같은 깨진 세션을 물려받아 "null identifier" AssertionFailure로 이어진다
// (부하테스트로 실측). REQUIRES_NEW로 매 시도마다 무조건 새 트랜잭션/세션을 강제한다.
@Component
public class CouponStateTransitionOperations {

    private final CouponStateLogRepository couponStateLogRepository;
    private final CouponIssueRepository couponIssueRepository;

    CouponStateTransitionOperations(CouponStateLogRepository couponStateLogRepository, CouponIssueRepository couponIssueRepository) {
        this.couponStateLogRepository = couponStateLogRepository;
        this.couponIssueRepository = couponIssueRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUsed(Long issueId, String requestId) {
        if (couponStateLogRepository.existsByRequestId(requestId)) {
            return;
        }
        CouponIssue issue = findIssueOrThrow(issueId);
        CouponStatus before = issue.getStatus();
        issue.markUsed();
        couponStateLogRepository.save(new CouponStateLog(issueId, before, issue.getStatus(), requestId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCanceled(Long issueId, String requestId) {
        if (couponStateLogRepository.existsByRequestId(requestId)) {
            return;
        }
        CouponIssue issue = findIssueOrThrow(issueId);
        CouponStatus before = issue.getStatus();
        issue.markCanceled();
        couponStateLogRepository.save(new CouponStateLog(issueId, before, issue.getStatus(), requestId));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReturnedToIssued(Long issueId, String requestId) {
        if (couponStateLogRepository.existsByRequestId(requestId)) {
            return;
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
