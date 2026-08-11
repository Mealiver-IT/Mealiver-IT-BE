package com.mealiverit.api.coupon.service;

import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// 04_아키텍처.txt 5.1절 순서 그대로. reserve()/rollback()만 StockReservationStrategy 구현체에 따라
// 달라지고(현재는 V2: 비관적 락), 그 외 로직은 버전 사다리 전 단계에서 공유된다(03_버전사다리 5절 통제 변인).
// 실제 트랜잭션 처리는 CouponIssueTransactionalOperations(별도 빈)에 위임한다 — 그 클래스의 주석 참고.
@Service
public class CouponIssueService {

    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueTransactionalOperations transactionalOperations;

    public CouponIssueService(CouponIssueRepository couponIssueRepository,
                               CouponIssueTransactionalOperations transactionalOperations) {
        this.couponIssueRepository = couponIssueRepository;
        this.transactionalOperations = transactionalOperations;
    }

    public IssueResult issue(Long userId, Long campaignId, String idempotencyKey) {
        // 1) 이미 처리된 요청인지 먼저 확인 (조회로 빠른 반환)
        return couponIssueRepository.findByIdempotencyKey(idempotencyKey)
                .map(IssueResult::alreadyProcessed)
                .orElseGet(() -> issueNew(userId, campaignId, idempotencyKey));
    }

    private IssueResult issueNew(Long userId, Long campaignId, String idempotencyKey) {
        try {
            return transactionalOperations.issueNew(userId, campaignId, idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            return transactionalOperations.recoverFromConflict(campaignId, userId, e);
        }
    }
}
