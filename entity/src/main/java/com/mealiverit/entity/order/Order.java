package com.mealiverit.entity.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// 04_아키텍처.txt: 계급 산정용 결제완료 이력. 실제 음식주문 UI는 프론트 정적 mockup이고,
// 이 테이블은 그 뒤에서 계급 계산만을 위해 존재하는 최소 스키마.
@Entity
@Table(name = "orders")
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private BigDecimal orderAmount;

    @Column(nullable = false)
    private BigDecimal paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private LocalDateTime orderedAt;

    private LocalDateTime completedAt;

    @Column(name = "coupon_issue_id")
    private Long couponIssueId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Order() {
        // JPA
    }

    public Order(Long userId, BigDecimal orderAmount, BigDecimal paidAmount, LocalDateTime orderedAt) {
        this.userId = userId;
        this.orderAmount = orderAmount;
        this.paidAmount = paidAmount;
        this.status = OrderStatus.COMPLETED;
        this.orderedAt = orderedAt;
    }

    //쿠폰 적용된 주문 생성용 오버로드 생성자
    public Order(Long userId, BigDecimal orderAmount, BigDecimal paidAmount, LocalDateTime orderedAt, Long couponIssueId) {
        this(userId, orderAmount, paidAmount, orderedAt);
        this.couponIssueId = couponIssueId;
    }

    public void cancel() {
        this.status = OrderStatus.CANCELED;
    }

    public void refund() {
        this.status = OrderStatus.REFUNDED;
    }

    public void complete(LocalDateTime completedAt) {
        this.status = OrderStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getOrderAmount() {
        return orderAmount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public LocalDateTime getOrderedAt() {
        return orderedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public Long getCouponIssueId() { return couponIssueId; }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
