package com.mealiverit.api.coupon.service;

import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// 04_아키텍처.txt 5.1절 순서 그대로. reserve()/rollback()만 StockReservationStrategy 구현체에 따라
// 달라지고(현재는 V2: 비관적 락), 그 외 로직은 버전 사다리 전 단계에서 공유된다(03_버전사다리 5절 통제 변인).
// 실제 트랜잭션 처리는 CouponIssuanceTransactionalOperations(별도 빈)에 위임한다 — 그 클래스의 주석 참고.
// 이름이 CouponIssueService가 아니라 CouponIssuanceService인 이유: dev 브랜치에 담당자 B가 상태전이/조회용
// CouponIssueService(markUsed/markCanceled/getIssuedCoupons)를 이미 같은 풀네임으로 merge했다. 클래스명
// 충돌을 피하기 위해 FCFS 발급(issue) 쪽을 이 이름으로 분리했다 — CouponIssue 엔티티 자체를 다루는
// "상태전이" 서비스와, "발급 행위(issuance)"를 다루는 이 서비스는 책임이 다르다.
@Service
public class CouponIssuanceService {

    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssuanceTransactionalOperations transactionalOperations;

    public CouponIssuanceService(CouponIssueRepository couponIssueRepository,
                                  CouponIssuanceTransactionalOperations transactionalOperations) {
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
