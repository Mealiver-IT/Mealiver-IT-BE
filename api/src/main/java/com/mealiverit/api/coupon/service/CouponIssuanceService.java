package com.mealiverit.api.coupon.service;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
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
//
// 2026-08-19 멘토링 피드백(DB 선반영 -> 스냅샷 생성 -> Redis 반영) 반영: DB 트랜잭션 시작 전에
// Redis 스냅샷으로 "확실한 품절"만 미리 걸러낸다. 재고 판단 자체는 여전히 DB(StockReservationStrategy)가
// 하고, 이 사전 필터는 이미 끝난 게 뻔한 요청이 DB 커넥션/락 대기열까지 안 가게 해서 부하를 줄이는
// 최적화일 뿐이다 - Redis 게이트(countReq/count 등 원자적 연산으로 발급 여부를 직접 결정)가 아니다.
@Service
public class CouponIssuanceService {

    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssuanceTransactionalOperations transactionalOperations;
    private final CampaignStockCache campaignStockCache;

    public CouponIssuanceService(CouponIssueRepository couponIssueRepository,
                                  CouponIssuanceTransactionalOperations transactionalOperations,
                                  CampaignStockCache campaignStockCache) {
        this.couponIssueRepository = couponIssueRepository;
        this.transactionalOperations = transactionalOperations;
        this.campaignStockCache = campaignStockCache;
    }

    public IssueResult issue(Long userId, Long campaignId, String idempotencyKey) {
        // 1) 이미 처리된 요청인지 먼저 확인 (조회로 빠른 반환)
        return couponIssueRepository.findByIdempotencyKey(idempotencyKey)
                .map(IssueResult::alreadyProcessed)
                .orElseGet(() -> issueNew(userId, campaignId, idempotencyKey));
    }

    private IssueResult issueNew(Long userId, Long campaignId, String idempotencyKey) {
        // 2) Redis 스냅샷 사전 필터. null(캐시 미스/Redis 장애)이거나 재고가 남아있으면 DB로 그대로
        // 보낸다 - 여기서 "재고 있음"으로 통과시켜도 되고 안 해도 되고는 DB가 최종 판단하므로 무해하다.
        // 값이 0 이하로 확실할 때만 DB(커넥션/락 대기열)를 안 타고 즉시 거절한다.
        Integer snapshot = campaignStockCache.getSnapshot(campaignId);
        if (snapshot != null && snapshot <= 0) {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }
        try {
            return transactionalOperations.issueNew(userId, campaignId, idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            return transactionalOperations.recoverFromConflict(campaignId, userId, e);
        }
    }
}
