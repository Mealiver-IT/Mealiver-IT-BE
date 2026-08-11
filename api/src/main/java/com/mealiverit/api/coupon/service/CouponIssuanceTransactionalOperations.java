package com.mealiverit.api.coupon.service;

import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.coupon.TierDiscountPolicy;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.entity.CouponIssue;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponRepository;
import com.mealiverit.entity.user.MembershipTier;
import com.mealiverit.entity.user.User;
import com.mealiverit.entity.user.UserRepository;
import java.math.BigDecimal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// CouponIssuanceService에서 분리된 이유: uk_campaign_user 위반(DataIntegrityViolationException)이 나면
// INSERT를 시도한 트랜잭션의 Hibernate 세션은 flush 실패로 오염되어 더 이상 쓸 수 없다(같은 세션으로
// rollback()/재조회를 계속하면 "AssertionFailure: has a null identifier" 발생 — 동시성 테스트로 실측).
// issueNew()의 예외가 프록시 경계를 넘어가야 트랜잭션이 깨끗하게 롤백되고, recoverFromConflict()가
// 완전히 새 트랜잭션/세션에서 재고 원복과 재조회를 수행한다. 같은 클래스 안에서 this.method() 자기호출로는
// 프록시를 안 타서 트랜잭션 경계가 안 나뉘기 때문에 별도 빈으로 분리했다.
@Component
class CouponIssuanceTransactionalOperations {

    private final CampaignRepository campaignRepository;
    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final UserRepository userRepository;
    private final StockReservationStrategy stockReservationStrategy;

    CouponIssuanceTransactionalOperations(CampaignRepository campaignRepository,
                                           CouponRepository couponRepository,
                                           CouponIssueRepository couponIssueRepository,
                                           UserRepository userRepository,
                                           StockReservationStrategy stockReservationStrategy) {
        this.campaignRepository = campaignRepository;
        this.couponRepository = couponRepository;
        this.couponIssueRepository = couponIssueRepository;
        this.userRepository = userRepository;
        this.stockReservationStrategy = stockReservationStrategy;
    }

    @Transactional
    IssueResult issueNew(Long userId, Long campaignId, String idempotencyKey) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
        // campaign은 @Version 엔티티라 findById(락 없음) 이후 findByIdForUpdate(락 있음)로 같은 row를
        // 다시 읽으면, 그 사이 다른 트랜잭션이 커밋할 경우 버전이 달라져
        // ObjectOptimisticLockingFailureException이 난다(동시성 테스트로 실측 확인함).
        // 락을 먼저 잡고 그 안에서 eligibility까지 판정 — "락 이전에 즉시 거부"라는 이상적 최적화는
        // 포기하지만, 이 프로젝트는 성능이 아니라 정확성이 평가축이라 트레이드오프가 맞다.
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
        MembershipTier userTier = user.getMembershipTier();
        if (!campaign.isEligible(userTier)) {
            throw new BusinessException(ErrorCode.MEMBERSHIP_TIER_NOT_ELIGIBLE);
        }

        boolean reserved = stockReservationStrategy.reserve(campaignId);
        if (!reserved) {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }

        Coupon coupon = couponRepository.findByCampaignId(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
        BigDecimal appliedDiscountValue = resolveDiscountValue(coupon, userTier);

        // uk_campaign_user, uk_idempotency_key가 최종 방어선. 위반 시 DataIntegrityViolationException은
        // 여기서 삼키지 않고 그대로 던져 트랜잭션을 롤백시킨다 — 복구는 recoverFromConflict()가 새 트랜잭션에서 한다.
        CouponIssue issue = CouponIssue.issue(campaignId, userId, idempotencyKey,
                coupon, userTier, appliedDiscountValue);
        couponIssueRepository.save(issue);
        return IssueResult.success(issue);
    }

    @Transactional
    IssueResult recoverFromConflict(Long campaignId, Long userId, DataIntegrityViolationException cause) {
        // unique 제약 위반 = 동시에 같은 요청/유저가 통과됨 → 기존 레코드를 찾아 반환.
        // 재고 원복(stockReservationStrategy.rollback)은 여기서 하지 않는다: issueNew()가
        // @Transactional인 채로 이 예외가 새어나갔으므로, 그 트랜잭션에서 일어난 reserve()의 재고
        // 차감은 Spring/DB가 이미 자동으로 롤백했다. 여기서 또 rollback()을 부르면 이중 복원이 되어
        // remainingStock이 실제보다 커진다(동시성 테스트로 실측 확인 — restoreStock()의
        // totalStock 초과 가드에 걸려 일부 요청만 조용히 실패하는 형태로 드러났었다).
        return couponIssueRepository.findByCampaignIdAndUserId(campaignId, userId)
                .map(IssueResult::alreadyProcessed)
                .orElseThrow(() -> cause);
    }

    // RATE 타입은 발급 시점 계급별 차등 할인율(04_아키텍처.txt 6.1절), FIXED 타입은 쿠폰 정책의 고정값 그대로.
    private BigDecimal resolveDiscountValue(Coupon coupon, MembershipTier userTier) {
        if (coupon.getDiscountType() == DiscountType.RATE) {
            return TierDiscountPolicy.rateFor(userTier);
        }
        return coupon.getDiscountValue();
    }
}
