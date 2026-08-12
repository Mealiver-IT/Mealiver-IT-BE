package com.mealiverit.api.coupon.service;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.coupon.dto.CouponIssueResponse;
import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.entity.CouponStateLog;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponStateLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CouponIssueService {

    private final CouponIssueRepository couponIssueRepository;
    private final CouponStateLogRepository couponStateLogRepository;

    public CouponIssueService(CouponIssueRepository couponIssueRepository,
                              CouponStateLogRepository couponStateLogRepository) {
        this.couponIssueRepository = couponIssueRepository;
        this.couponStateLogRepository = couponStateLogRepository;
    }

    //결제 페이지 토글 UI용 - 사용 가능(ISSUED)한 쿠폰만 반환
    public List<CouponIssueResponse> getIssuedCoupons(Long userId) {
        return couponIssueRepository.findByUserIdAndStatus(userId, CouponStatus.ISSUED).stream()
                .map(CouponIssueResponse::from)
                .toList();
    }

    //OrderService가 결제완료(POST /api/orders) 처리 중 내부 호출. 별도 public API 없음
    //requestId: 호출측(OrderService)이 재시도 시에도 동일하게 넘겨야 하는 멱등키
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

    private CouponIssue findIssueOrThrow(Long issueId) {
        return couponIssueRepository.findById(issueId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));
    }
}
