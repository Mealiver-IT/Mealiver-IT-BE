package com.mealiverit.api.coupon.service;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.entity.CouponStateLog;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.CouponStateChangeReason;
import com.mealiverit.entity.coupon.repository.CouponStateLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// CouponIssueService에서 분리된 이유: @Retryable + @Transactional을 같은 메서드에 겹쳐 쓰면
// 재시도가 이전 시도의 트랜잭션에 편승해 깨진 세션을 물려받을 수 있어 별도 빈으로 분리했다.
// 호출부(OrderService)에서 @Transactional을 제거해 이 메서드를 호출하는 시점에 편승할
// 바깥 트랜잭션 자체가 없어졌으므로, REQUIRES_NEW 없이 기본 REQUIRED로도 매 시도(1차/재시도
// 모두)가 항상 독립된 새 트랜잭션/세션을 받는다. REQUIRES_NEW는 매 요청마다 커넥션을 2개씩
// 쓰게 만들어 커넥션 풀 고갈을 유발했다(부하테스트로 실측) - 이제 필요 없음.
@Component
public class CouponStateTransitionOperations {

    private final CouponStateLogRepository couponStateLogRepository;
    private final CouponIssueRepository couponIssueRepository;

    CouponStateTransitionOperations(CouponStateLogRepository couponStateLogRepository, CouponIssueRepository couponIssueRepository) {
        this.couponStateLogRepository = couponStateLogRepository;
        this.couponIssueRepository = couponIssueRepository;
    }

    @Transactional
    public void markUsed(Long issueId, String requestId) {
        if (couponStateLogRepository.existsByRequestId(requestId)) {
            return;
        }
        CouponIssue issue = findIssueOrThrow(issueId);
        CouponStatus before = issue.getStatus();
        issue.markUsed();
        couponStateLogRepository.save(new CouponStateLog(issueId, before, issue.getStatus(), requestId, CouponStateChangeReason.ORDER_PAYMENT));
    }

    @Transactional
    public void markCanceled(Long issueId, String requestId) {
        if (couponStateLogRepository.existsByRequestId(requestId)) {
            return;
        }
        CouponIssue issue = findIssueOrThrow(issueId);
        CouponStatus before = issue.getStatus();
        issue.markCanceled();
        couponStateLogRepository.save(new CouponStateLog(issueId, before, issue.getStatus(), requestId, CouponStateChangeReason.ADMIN_REVOKE));
    }

    @Transactional
    public void markReturnedToIssued(Long issueId, String requestId) {
        if (couponStateLogRepository.existsByRequestId(requestId)) {
            return;
        }
        CouponIssue issue = findIssueOrThrow(issueId);
        CouponStatus before = issue.getStatus();
        issue.markReturnedToIssued();
        couponStateLogRepository.save(new CouponStateLog(issueId, before, issue.getStatus(), requestId, CouponStateChangeReason.ORDER_CANCEL));
    }

    private CouponIssue findIssueOrThrow(Long issueId) {
        return couponIssueRepository.findById(issueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
    }
}
