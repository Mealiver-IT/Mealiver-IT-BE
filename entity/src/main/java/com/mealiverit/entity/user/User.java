package com.mealiverit.entity.user;

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

@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String loginId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipTier membershipTier;

    private LocalDateTime tierCalculatedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected User() {
        // JPA
    }

    public User(String loginId, String name, String phone, String email) {
        this(loginId, name, phone, email, MembershipTier.PRIVATE);
    }

    public User(String loginId, String name, String phone, String email, MembershipTier membershipTier) {
        this.loginId = loginId;
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.membershipTier = membershipTier;
    }

    // MembershipTierBatchJob이 매월 1일 재산정 결과를 반영할 때 사용 (04_아키텍처.txt 6.2절)
    public void changeTier(MembershipTier newTier, LocalDateTime calculatedAt) {
        this.membershipTier = newTier;
        this.tierCalculatedAt = calculatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getLoginId() {
        return loginId;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public MembershipTier getMembershipTier() {
        return membershipTier;
    }

    public LocalDateTime getTierCalculatedAt() {
        return tierCalculatedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
