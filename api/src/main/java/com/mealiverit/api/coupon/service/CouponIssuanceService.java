package com.mealiverit.api.coupon.service;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponRepository;
import com.mealiverit.entity.user.MembershipTier;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CouponIssuanceService.class);

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
            // 되돌려야 하지만 - 2026-08-27 실측 이후엔 "무조건 먼저 되돌리기" 전에 반드시
            // recoverFromInsertFailure()에서 실제 커밋 여부부터 확인해야 한다(아래 주석 참고).
            try {
                return transactionalOperations.insertCouponIssue(userId, campaignId, idempotencyKey, coupon, userTier);
            } catch (RuntimeException e) {
                return recoverFromInsertFailure(campaignId, userId, idempotencyKey, e);
            }
        } finally {
            duplicateGuard.release(campaignId, userId);
        }
    }

    // 2026-08-27 실측(race.js - 유저 20,000명이 전부 서로 다른 userId로 딱 1번씩만 요청하는
    // 시나리오, 즉 uk_campaign_user 경합이 원천적으로 불가능한 조건): 10,000 재고 캠페인에서
    // 10,261건이 발급되는 초과발급이 재현됐다. 원인은 insertCouponIssue()가 예외를 던져도
    // "실제로는 이미 DB에 커밋된" 애매한 경우(ambiguous outcome - 극한 동시성에서 HikariCP
    // 커넥션 문제 등으로 클라이언트(앱)만 실패로 오인)가 있는데, 예전 코드는 이 경우를 구분 안
    // 하고 무조건 먼저 재고부터 복원했다 - 이미 정당하게 소비된 슬롯을 또 풀어줘서 다른 유저가
    // 가져가버렸다(샤드 테이블 합계는 정확히 0/10000으로 맞아떨어지는데도 실제 발급 건수만
    // 초과 - 재고 판단 로직 자체가 아니라 이 복원 순서가 원인이었음).
    //
    // 수정: 반드시 idempotencyKey로 먼저 "이 시도 자신"이 실제로 커밋됐는지 확인한다 -
    // (campaignId, userId)만으로 확인하면 안 된다. 같은 유저가 다른 idempotencyKey로 진짜
    // 재시도한 경우(예: 클라이언트 타임아웃 후 재시도)엔 그 유저의 기존 행이 "다른 시도"의
    // 결과일 뿐이라, 이번 시도 자신이 확보한 재고는 그대로 새는 채 방치되면 안 된다(반대 방향
    // 버그 - 재고 유실). 그래서:
    //   1) 이번 idempotencyKey로 이미 커밋됐으면(ambiguous outcome) 이 시도 자체가 성공한
    //      것이므로 재고를 복원하지 않고 그대로 성공 응답을 돌려준다.
    //   2) 아니면 이번 시도가 확보한 재고는 진짜로 안 쓰였으므로 복원부터 하고, 그래도 같은
    //      유저가 "다른" idempotencyKey로 이미 성공한 건이 있으면(진짜 동시 경합) 그 기존
    //      건을 성공 응답으로 돌려준다 - 없으면 원래 예외를 그대로 던진다.
    private IssueResult recoverFromInsertFailure(Long campaignId, Long userId, String idempotencyKey,
                                                  RuntimeException cause) {
        Optional<CouponIssue> ownAttempt = couponIssueRepository.findByIdempotencyKey(idempotencyKey);
        if (ownAttempt.isPresent()) {
            log.warn("발급 INSERT가 예외를 던졌지만 이번 시도 자신이 실제로는 이미 커밋되어 있음을 "
                            + "확인함(ambiguous outcome로 추정) - 재고 복원 생략. "
                            + "campaignId={}, userId={}, 원래 예외={}",
                    campaignId, userId, cause.toString());
            return IssueResult.alreadyProcessed(ownAttempt.get());
        }
        compensateStockRollbackSafely(campaignId, userId, cause);
        return couponIssueRepository.findByCampaignIdAndUserId(campaignId, userId)
                .map(IssueResult::alreadyProcessed)
                .orElseThrow(() -> cause);
    }

    // 2026-08-22 실측(round-06, HikariCP 풀 고갈 상태): compensateStockRollback() 자체가 새
    // 커넥션을 못 받아 실패하는 사례가 재현됨 - 이 호출을 그대로 두면 그 예외가 catch 블록을
    // 빠져나가면서 원래 실패 원인(cause)을 덮어쓰고, reserveStock()에서 이미 차감된 재고는
    // 영원히 원복되지 않은 채 사라진다(재고 소진 카운터와 실제 발급 건수가 영구히 어긋남 -
    // 초과발급이 아니라 "유실" 방향). 여기서 롤백 실패를 삼켜서 원래 흐름(cause 기준 처리)은
    // 그대로 진행하되, 반드시 ERROR로 로그를 남겨 정합성 검증 배치/수동 확인으로 이어지게 한다.
    // 근본 해결은 아니다 - 커넥션이 정말 없으면 여기서 할 수 있는 건 "조용히 사라지지 않게
    // 만드는 것"까지다.
    private void compensateStockRollbackSafely(Long campaignId, Long userId, Exception cause) {
        try {
            transactionalOperations.compensateStockRollback(campaignId);
        } catch (Exception rollbackFailure) {
            log.error("재고 원복 실패 - 재고 유실 가능성, 수동 정합성 확인 필요 "
                            + "(campaignId={}, userId={}, 원래 실패 원인={})",
                    campaignId, userId, cause.toString(), rollbackFailure);
        }
    }
}
