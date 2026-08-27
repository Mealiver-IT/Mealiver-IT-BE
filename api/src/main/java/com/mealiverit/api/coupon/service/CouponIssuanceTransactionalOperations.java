package com.mealiverit.api.coupon.service;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.api.coupon.notification.CouponIssuedEvent;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.campaign.CampaignStatus;
import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.coupon.TierDiscountPolicy;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.user.MembershipTier;
import com.mealiverit.entity.user.User;
import com.mealiverit.entity.user.UserRepository;
import java.math.BigDecimal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// CouponIssuanceService에서 분리된 이유: uk_campaign_user 위반(DataIntegrityViolationException)이 나면
// INSERT를 시도한 트랜잭션의 Hibernate 세션은 flush 실패로 오염되어 더 이상 쓸 수 없다(같은 세션으로
// 재조회를 계속하면 "AssertionFailure: has a null identifier" 발생 — 동시성 테스트로 실측). 각 단계의
// 예외가 프록시 경계를 넘어가야 트랜잭션이 깨끗하게 롤백되고, 복구는 완전히 새 트랜잭션/세션에서
// 수행된다. 같은 클래스 안에서 this.method() 자기호출로는 프록시를 안 타서 트랜잭션 경계가 안
// 나뉘기 때문에, 아래 메서드들은 반드시 CouponIssuanceService(다른 빈)에서 순서대로 호출돼야 한다.
//
// 2026-08-27: insertCouponIssue() 실패 시 "이미 커밋됐는지 먼저 확인" 하는 조회
// (recoverFromInsertFailure)는 CouponIssuanceService 쪽에 있다 - insertCouponIssue()의
// @Transactional이 이미 완전히 종료(롤백)된 뒤에 실행되는 별개의 새 트랜잭션이라, 여기 세션
// 오염 문제와 무관하게 안전하다(couponIssueRepository 호출 자체가 Spring Data JPA 기본 동작으로
// 매번 자기만의 트랜잭션을 새로 연다).
//
// 락 구간 축소(2026-08-19): 예전엔 캠페인 락 획득 -> eligibility 판정 -> 재고 차감 -> 쿠폰 정책
// 조회 -> coupon_issue INSERT까지 하나의 트랜잭션 안에서 처리했다. 캠페인 row 락은 트랜잭션이
// 끝날 때(커밋)까지 유지되므로, 락과 무관한 쿠폰 조회/INSERT까지 락을 붙잡은 채 처리하면 그만큼
// 락 순환이 느려진다(hot row 경합 심화 원인 중 하나 — 2026-08-19 부하테스트로 확인). 그래서
// "재고 확보"(락 필요)와 "발급 기록 INSERT"(락 불필요)를 별도 트랜잭션으로 쪼갰다: reserveStock()이
// 끝나는 즉시 락이 풀리고, insertCouponIssue()는 락 없이 진행된다.
//
// 대신 두 트랜잭션 사이에 실패가 생기면(예: insertCouponIssue의 uk 제약 위반) reserveStock()의
// 재고 차감은 이미 커밋된 뒤라, "같은 트랜잭션이라 자동 롤백된다"는 예전 전제가 더는 성립하지
// 않는다 — compensateStockRollback()으로 명시적으로 되돌려야 한다(호출측 CouponIssuanceService 참고).
@Component
class CouponIssuanceTransactionalOperations {

    private final CampaignRepository campaignRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final UserRepository userRepository;
    private final StockReservationStrategy stockReservationStrategy;
    private final ApplicationEventPublisher eventPublisher;

    CouponIssuanceTransactionalOperations(CampaignRepository campaignRepository,
                                           CouponIssueRepository couponIssueRepository,
                                           UserRepository userRepository,
                                           StockReservationStrategy stockReservationStrategy,
                                           ApplicationEventPublisher eventPublisher) {
        this.campaignRepository = campaignRepository;
        this.couponIssueRepository = couponIssueRepository;
        this.userRepository = userRepository;
        this.stockReservationStrategy = stockReservationStrategy;
        this.eventPublisher = eventPublisher;
    }

    // 2026-08-20 부하테스트(coupon_mixed_5k_x4.js) 실측: 예전엔 findByIdForUpdate()로 캠페인 row를
    // 먼저 잠그고 그 안에서 eligibility까지 판정했다(당시엔 findById 이후 findByIdForUpdate로 같은
    // @Version row를 다시 읽으면 ObjectOptimisticLockingFailureException이 났었음) - 그 결과 락이
    // "애플리케이션 로직이 끝날 때까지" 유지돼 hot row 경합을 키웠다.
    // stockReservationStrategy.reserve()가 이제 엔티티를 로드하지 않는 벌크 UPDATE라서(같은
    // 영속성 컨텍스트에서 campaign을 두 번 로드하는 게 아님) 그 문제 없이 eligibility/오픈여부
    // 판정을 락 없는 조회로 먼저 끝낼 수 있다. 트레이드오프: 이 조회와 실제 차감 UPDATE 사이
    // 아주 짧은 순간 캠페인 상태가 바뀔 이론적 여지가 있지만(관리자가 그 찰나에 캠페인을 닫는 등),
    // 캠페인 상태 전환은 라이브 발급 폭주 중에 일어나는 정상 이벤트가 아니라 감수 가능한 트레이드오프로
    // 판단했다 - 재고 자체는 이 조회와 무관하게 decreaseStockIfAvailable()의 WHERE 조건이 실행
    // 시점 최신값으로 다시 확인하므로 초과발급 위험은 없다.
    @Transactional
    MembershipTier reserveStock(Long userId, Long campaignId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
        // FR-FCFS-031: "캠페인 미오픈"은 품절/등급미달과 구분되는 별도 실패 사유다.
        // READY(아직 시작 전)/CLOSED(이미 종료) 둘 다 발급 대상이 아니므로 OPEN만 통과시킨다.
        if (campaign.getStatus() != CampaignStatus.OPEN) {
            throw new BusinessException(ErrorCode.CAMPAIGN_NOT_OPEN);
        }
        MembershipTier userTier = user.getMembershipTier();
        if (!campaign.isEligible(userTier)) {
            throw new BusinessException(ErrorCode.MEMBERSHIP_TIER_NOT_ELIGIBLE);
        }

        // 캠페인 row에 락이 걸리는 유일한 구간 - decreaseStockIfAvailable()의 단일 UPDATE 문
        // 실행 동안만 InnoDB가 암묵적으로 행을 잠근다.
        boolean reserved = stockReservationStrategy.reserve(campaignId);
        if (!reserved) {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }
        return userTier;
    }

    // 락과 무관한 INSERT만 별도 트랜잭션으로 분리. reserveStock()이 이미 재고를 확정했으므로
    // 여기서는 순수하게 발급 기록만 남긴다 - 캠페인 row는 건드리지 않는다.
    @Transactional
    IssueResult insertCouponIssue(Long userId, Long campaignId, String idempotencyKey,
                                   Coupon coupon, MembershipTier userTier) {
        BigDecimal appliedDiscountValue = resolveDiscountValue(coupon, userTier);

        // uk_campaign_user, uk_idempotency_key가 최종 방어선. 위반 시 DataIntegrityViolationException은
        // 여기서 삼키지 않고 그대로 던져 이 트랜잭션만 롤백시킨다 — 실제 커밋 여부 확인
        // (recoverFromInsertFailure)과 필요시 재고 원복(compensateStockRollback)은 호출측
        // (CouponIssuanceService)이 이 순서로 처리한다.
        CouponIssue issue = CouponIssue.issue(campaignId, userId, idempotencyKey,
                coupon, userTier, appliedDiscountValue);
        couponIssueRepository.save(issue);
        // 신규 발급 성공 시(이 지점에 도달했다는 건 uk 제약 위반 없이 INSERT가 끝났다는 뜻)에만 발행.
        // 발급 트랜잭션과 알림 발송을 분리하기 위한 이벤트 — 실제 발송은 CouponIssuedNotificationListener가
        // 이 트랜잭션이 커밋된 뒤(@TransactionalEventListener AFTER_COMMIT)에만 수행한다.
        eventPublisher.publishEvent(new CouponIssuedEvent(userId, issue.getCouponCode(), campaignId));
        return IssueResult.success(issue);
    }

    // insertCouponIssue()가 실패했을 때, 이미 커밋된 reserveStock()의 재고 차감을 되돌린다.
    // 별도의 새 트랜잭션에서 캠페인 row를 다시 잠깐 잠그고(원복 자체는 원자적 단일 UPDATE 수준이라
    // 매우 짧다) remainingStock을 복원한다.
    @Transactional
    void compensateStockRollback(Long campaignId) {
        stockReservationStrategy.rollback(campaignId);
    }

    // RATE 타입은 발급 시점 계급별 차등 할인율(04_아키텍처.txt 6.1절), FIXED 타입은 쿠폰 정책의 고정값 그대로.
    private BigDecimal resolveDiscountValue(Coupon coupon, MembershipTier userTier) {
        if (coupon.getDiscountType() == DiscountType.RATE) {
            return TierDiscountPolicy.rateFor(userTier);
        }
        return coupon.getDiscountValue();
    }
}
