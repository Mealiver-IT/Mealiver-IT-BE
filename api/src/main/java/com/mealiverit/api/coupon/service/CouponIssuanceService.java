package com.mealiverit.api.coupon.service;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponRepository;
import com.mealiverit.entity.user.MembershipTier;
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
//
// 2026-08-19 락 구간 축소: 쿠폰 정책 조회는 캠페인 락과 무관한 정적 데이터라 락을 잡기 전에
// 미리 조회해둔다(CouponIssuanceTransactionalOperations 주석 참고). reserveStock()(락 있음)과
// insertCouponIssue()(락 없음)를 별도 트랜잭션으로 호출하므로, insertCouponIssue() 이후 어떤
// 실패든(uk 제약 위반이든 그 외 예외든) 반드시 compensateStockRollback()으로 재고를 되돌려야
// 한다 - 더는 "같은 트랜잭션이라 자동 롤백된다"는 전제가 없다.
@Service
public class CouponIssuanceService {

    private final CouponIssueRepository couponIssueRepository;
    private final CouponRepository couponRepository;
    private final CouponIssuanceTransactionalOperations transactionalOperations;
    private final CampaignStockCache campaignStockCache;
    private final CouponIssuanceDuplicateGuard duplicateGuard;

    public CouponIssuanceService(CouponIssueRepository couponIssueRepository,
                                  CouponRepository couponRepository,
                                  CouponIssuanceTransactionalOperations transactionalOperations,
                                  CampaignStockCache campaignStockCache,
                                  CouponIssuanceDuplicateGuard duplicateGuard) {
        this.couponIssueRepository = couponIssueRepository;
        this.couponRepository = couponRepository;
        this.transactionalOperations = transactionalOperations;
        this.campaignStockCache = campaignStockCache;
        this.duplicateGuard = duplicateGuard;
    }

    public IssueResult issue(Long userId, Long campaignId, String idempotencyKey) {
        // 1) 이미 처리된 요청인지 먼저 확인 (조회로 빠른 반환)
        return couponIssueRepository.findByIdempotencyKey(idempotencyKey)
                .map(IssueResult::alreadyProcessed)
                .orElseGet(() -> issueNew(userId, campaignId, idempotencyKey));
    }

    private IssueResult issueNew(Long userId, Long campaignId, String idempotencyKey) {
        // 2) 중복요청 사전 필터. 같은 (campaignId, userId)로 이미 진행 중인 요청이 있으면 캠페인 락을
        // 아예 안 타고 즉시 거절한다 - idempotencyKey가 요청마다 달라서(재시도 등) 위의 findByIdempotencyKey
        // 로는 못 거르는 케이스를 여기서 대신 거른다(2026-08-19 부하테스트 실측 - 다르지 않으면
        // 이 가드 자체가 무의미).
        if (!duplicateGuard.tryAcquire(campaignId, userId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST_IN_PROGRESS);
        }

        // 2026-08-22 부하테스트 실측: TTL 만료에만 기대면 부하로 처리 시간이 늘어나는 순간
        // 이 가드가 무력화된다(CouponIssuanceDuplicateGuard 주석 참고) - 그래서 이 요청이 어떻게
        // 끝나든(성공/거절/예외) 반드시 release()로 즉시 해제한다.
        try {
            // 3) Redis 스냅샷 사전 필터. null(캐시 미스/Redis 장애)이거나 재고가 남아있으면 DB로 그대로
            // 보낸다 - 여기서 "재고 있음"으로 통과시켜도 되고 안 해도 되고는 DB가 최종 판단하므로 무해하다.
            // 값이 0 이하로 확실할 때만 DB(커넥션/락 대기열)를 안 타고 즉시 거절한다.
            Integer snapshot = campaignStockCache.getSnapshot(campaignId);
            if (snapshot != null && snapshot <= 0) {
                throw new BusinessException(ErrorCode.SOLD_OUT);
            }

            // 4) 쿠폰 정책은 캠페인 락과 무관한 정적 데이터라 락 밖에서 미리 조회 - 락을 짧게 유지하는 게 목적.
            Coupon coupon = couponRepository.findByCampaignId(campaignId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));

            // 5) 캠페인 row 락을 잡는 유일한 구간. 리턴하는 순간 락이 풀린다.
            MembershipTier userTier = transactionalOperations.reserveStock(userId, campaignId);

            // 6) 락 없이 발급 기록만 INSERT. 실패하면(uk 제약 위반이든 그 외든) 5)에서 확정한 재고를
            // 반드시 되돌려야 한다.
            try {
                return transactionalOperations.insertCouponIssue(userId, campaignId, idempotencyKey, coupon, userTier);
            } catch (DataIntegrityViolationException e) {
                transactionalOperations.compensateStockRollback(campaignId);
                return transactionalOperations.recoverFromConflict(campaignId, userId, e);
            } catch (RuntimeException e) {
                transactionalOperations.compensateStockRollback(campaignId);
                throw e;
            }
        } finally {
            duplicateGuard.release(campaignId, userId);
        }
    }
}
