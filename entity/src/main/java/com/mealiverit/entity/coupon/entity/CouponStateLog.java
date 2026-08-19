package com.mealiverit.entity.coupon.entity;

import com.mealiverit.entity.coupon.CouponStatus;
import com.mealiverit.entity.coupon.CouponStateChangeReason;
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

// 상태전이 감사로그 — 검증 배치가 "이력 vs 카운터" 비교할 근거 (04_아키텍처.txt 1, 5.2절)
@Entity
@Table(name = "coupon_state_log")
@EntityListeners(AuditingEntityListener.class)
public class CouponStateLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_issue_id", nullable = false)
    private Long couponIssueId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CouponStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponStatus toStatus;

    // 상태변경 요청의 idempotency key. uk_state_log_request가 FR-CPS-005 멱등성의 최종 방어선.
    @Column(nullable = false, unique = true)
    private String requestId;

    // 상태가 왜 바뀌었는지 (상태전이 세분화 대응)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponStateChangeReason reason;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected CouponStateLog() {
        // JPA
    }

    public CouponStateLog(Long couponIssueId, CouponStatus fromStatus, CouponStatus toStatus, String requestId, CouponStateChangeReason reason) {
        this.couponIssueId = couponIssueId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.requestId = requestId;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public Long getCouponIssueId() {
        return couponIssueId;
    }

    public CouponStatus getFromStatus() {
        return fromStatus;
    }

    public CouponStatus getToStatus() {
        return toStatus;
    }

    public String getRequestId() {
        return requestId;
    }

    public CouponStateChangeReason getReason() { return reason; }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
