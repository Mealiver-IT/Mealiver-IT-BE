package com.mealiverit.entity.membership;

import com.mealiverit.entity.user.MembershipTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// 계급 재산정 배치 감사로그 — CouponStateLog와 동일 패턴 (04_아키텍처.txt 1절)
@Entity
@Table(name = "membership_tier_log")
@EntityListeners(AuditingEntityListener.class)
public class MembershipTierLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MembershipTier fromTier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipTier toTier;

    @Column(nullable = false)
    private int orderCount;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime calculatedAt;

    protected MembershipTierLog() {
        // JPA
    }

    public MembershipTierLog(Long userId, MembershipTier fromTier, MembershipTier toTier, int orderCount) {
        this.userId = userId;
        this.fromTier = fromTier;
        this.toTier = toTier;
        this.orderCount = orderCount;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public MembershipTier getFromTier() {
        return fromTier;
    }

    public MembershipTier getToTier() {
        return toTier;
    }

    public int getOrderCount() {
        return orderCount;
    }

    public LocalDateTime getCalculatedAt() {
        return calculatedAt;
    }
}
