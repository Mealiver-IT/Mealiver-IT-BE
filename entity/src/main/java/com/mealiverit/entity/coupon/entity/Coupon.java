package com.mealiverit.entity.coupon.entity;

import com.mealiverit.entity.coupon.DiscountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

// 캠페인이 발급하는 쿠폰 정책 마스터. 캠페인:쿠폰 1:1로 단순화 가능 (04_아키텍처.txt 1절)
@Entity
@Table(name = "coupon")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscountType discountType;

    // RATE 타입 + 계급별 차등 캠페인의 경우 기본값(이등병/일병 기준). 실제 적용률은
    // TierDiscountPolicy(04_아키텍처.txt 6.1절)가 판단해 CouponIssue.discountValue에 스냅샷된다.
    @Column(nullable = false)
    private BigDecimal discountValue;

    private BigDecimal minOrderAmount;

    private BigDecimal maxDiscountAmount;

    // 발급 시점(issued_at)으로부터 유효 시간(시간 단위). 반드시 1 이상 — 0이면 발급 즉시 만료된 죽은 쿠폰.
    @Column(nullable = false)
    private int validHours;

    protected Coupon() {
        // JPA
    }

    public Coupon(Long campaignId, DiscountType discountType, BigDecimal discountValue,
                  BigDecimal minOrderAmount, BigDecimal maxDiscountAmount, int validHours) {
        if (validHours < 1) {
            throw new IllegalArgumentException("validHours must be >= 1");
        }
        this.campaignId = campaignId;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.minOrderAmount = minOrderAmount;
        this.maxDiscountAmount = maxDiscountAmount;
        this.validHours = validHours;
    }

    public Long getId() {
        return id;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public DiscountType getDiscountType() {
        return discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
    }

    public BigDecimal getMinOrderAmount() {
        return minOrderAmount;
    }

    public BigDecimal getMaxDiscountAmount() {
        return maxDiscountAmount;
    }

    public int getValidHours() {
        return validHours;
    }
}
