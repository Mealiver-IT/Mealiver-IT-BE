package com.mealiverit.api.coupon.entity;

import com.mealiverit.api.common.entity.BaseTimeEntity;
import com.mealiverit.api.coupon.CouponStatus;
import com.mealiverit.api.coupon.DiscountType;
import com.mealiverit.api.coupon.InvalidStateTransitionException;
import com.mealiverit.api.user.MembershipTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// 발급 이력 = 상태 관리 핵심 테이블(300만 건 대상). 04_아키텍처.txt 1, 4~5절.
@Entity
@Table(name = "coupon_issue",
        uniqueConstraints = @UniqueConstraint(name = "uk_campaign_user", columnNames = {"campaign_id", "user_id"}),
        indexes = @Index(name = "idx_ci_status_valid_until", columnList = "status,valid_until"))
public class CouponIssue extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true)
    private String couponCode;

    // 아래 세 필드는 발급 시점 스냅샷 — 이후 Coupon/유저 계급이 바뀌어도 불변 (FR-FCFS-002)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscountType discountType;

    @Column(nullable = false)
    private BigDecimal discountValue;

    private BigDecimal maxDiscountAmount;

    // 발급 시점 유저 계급 스냅샷. 검증쿼리(e)가 users.membership_tier(변동값) 대신 이 값으로
    // 판정해야 계급 재산정 후 false positive가 없다 (01_설계보완_검토안.txt 0-3).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipTier issuedMembershipTier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponStatus status;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private LocalDateTime issuedAt;

    // 만료 마감 시점 스냅샷 = issuedAt + coupon.validHours, 발급 시점 고정 (FR-CPS-003)
    @Column(nullable = false)
    private LocalDateTime validUntil;

    private LocalDateTime usedAt;
    private LocalDateTime canceledAt;
    private LocalDateTime expiredAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected CouponIssue() {
        // JPA
    }

    // coupon: 발급 대상 캠페인의 쿠폰 정책(discountType/maxDiscountAmount/validHours의 출처).
    // appliedDiscountValue: TierDiscountPolicy.rateFor(issuedMembershipTier)로 호출측이 미리 계산한 값.
    public static CouponIssue issue(Long campaignId, Long userId, String idempotencyKey,
                                     Coupon coupon, MembershipTier issuedMembershipTier,
                                     BigDecimal appliedDiscountValue) {
        CouponIssue issue = new CouponIssue();
        LocalDateTime now = LocalDateTime.now();
        issue.campaignId = campaignId;
        issue.userId = userId;
        issue.idempotencyKey = idempotencyKey;
        issue.couponCode = "CPN-" + UUID.randomUUID();
        issue.discountType = coupon.getDiscountType();
        issue.discountValue = appliedDiscountValue;
        issue.maxDiscountAmount = coupon.getMaxDiscountAmount();
        issue.issuedMembershipTier = issuedMembershipTier;
        issue.status = CouponStatus.ISSUED;
        issue.issuedAt = now;
        issue.validUntil = now.plusHours(coupon.getValidHours());
        return issue;
    }

    public void markUsed() {
        transitionTo(CouponStatus.USED);
        this.usedAt = LocalDateTime.now();
    }

    public void markCanceled() {
        transitionTo(CouponStatus.CANCELED);
        this.canceledAt = LocalDateTime.now();
    }

    // 주문 취소 시 본인 재사용 가능하도록 복귀
    // allow_reissue_on_cancel(재고 미복구)과는 별개 축 - 이 쿠폰 개별 건의 재사용 여부
    // usedAt은 더 이상 유효하지 않으므로 초기화. valid_until은 발급 시점 스냅샷 그대로 유지
    // 이미 기한이 지났으면 다음 만료 배치 (ISSUED -> EXPIRED)가 알아서 처리하므로 여기서 별도 체크 불필요
    public void markReturnedToIssued() {
        transitionTo(CouponStatus.ISSUED);
        this.usedAt = null;
    }

    // 만료 배치 전용 (04_아키텍처.txt 3절): UPDATE ... WHERE status='ISSUED' AND valid_until < now로
    // 스캔된 row에만 호출되므로 항상 ISSUED -> EXPIRED 전이만 발생한다.
    public void markExpired(LocalDateTime expiredAt) {
        transitionTo(CouponStatus.EXPIRED);
        this.expiredAt = expiredAt;
    }

    private void transitionTo(CouponStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(status, target);
        }
        this.status = target;
    }

    public Long getId() {
        return id;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public DiscountType getDiscountType() {
        return discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
    }

    public BigDecimal getMaxDiscountAmount() {
        return maxDiscountAmount;
    }

    public MembershipTier getIssuedMembershipTier() {
        return issuedMembershipTier;
    }

    public CouponStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public LocalDateTime getValidUntil() {
        return validUntil;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public LocalDateTime getCanceledAt() {
        return canceledAt;
    }

    public LocalDateTime getExpiredAt() {
        return expiredAt;
    }

    public Long getVersion() {
        return version;
    }
}
