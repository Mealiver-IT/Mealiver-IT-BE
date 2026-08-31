package com.mealiverit.api.campaign.entity;

import com.mealiverit.api.campaign.CampaignStatus;
import com.mealiverit.api.campaign.CampaignType;
import com.mealiverit.api.campaign.InvalidCampaignStateTransitionException;
import com.mealiverit.api.user.MembershipTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

@Entity
@Table(name = "campaign")
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int totalStock;

    @Column(nullable = false)
    private int remainingStock;

    private LocalDateTime openAt;

    private LocalDateTime closeAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CampaignStatus status;

    // NULL이면 전 회원 대상, 설정 시 회원 전용 쿠폰 (04_아키텍처.txt 1, 6절)
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MembershipTier minMembershipTier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CampaignType campaignType;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Campaign() {
        // JPA
    }

    public Campaign(String name, int totalStock, MembershipTier minMembershipTier) {
        this(name, totalStock, minMembershipTier, CampaignType.FCFS, null);
    }

    public Campaign(String name, int totalStock, MembershipTier minMembershipTier, CampaignType campaignType) {
        this(name, totalStock, minMembershipTier, campaignType, null);
    }

    // 오픈시간 예약 - 생성 시점에 scheduledOpenAt을 넘기면 READY 상태를 유지한 채 기존 openAt 컬럼에 미리 저장해둔다(openAt() 호출 전이라 status는 그대로 READY)
    // 새 컬럼 없이 openAt을 "예정 시각"으로 먼저 쓰고, 나중에 open()이 호출되면 같은 컬럼에 실제 오픈된 시각으로 덮어써짐
    // CampaignScheduledOpenBatchJob이 이 값을 보고 자동으로 open()을 호출한다.
    public Campaign(String name, int totalStock, MembershipTier minMembershipTier, LocalDateTime scheduledOpenAt) {
        this(name, totalStock, minMembershipTier, CampaignType.FCFS, scheduledOpenAt);
    }

    public Campaign(String name, int totalStock, MembershipTier minMembershipTier, CampaignType campaignType, LocalDateTime scheduledOpenAt) {
        this(name, totalStock, minMembershipTier, campaignType, scheduledOpenAt, null);
    }

    // scheduledCloseAt도 같은 방식으로 closeAt 컬럼에 미리 저장해둔다 - open()이 호출될 때(수동이든
    // CampaignScheduledOpenBatchJob의 자동 오픈이든) 이 값을 그대로 넘겨줘야 덮어써지지 않고 살아남는다
    // (2026-08-26 추가: 생성 폼에 마감 예약 시각 필드가 없어서 늘 무기한으로만 열렸던 걸 보완).
    public Campaign(String name, int totalStock, MembershipTier minMembershipTier, CampaignType campaignType,
                     LocalDateTime scheduledOpenAt, LocalDateTime scheduledCloseAt) {
        this.name = name;
        this.totalStock = totalStock;
        this.remainingStock = totalStock;
        this.status = CampaignStatus.READY;
        this.minMembershipTier = minMembershipTier;
        this.campaignType = campaignType;
        this.openAt = scheduledOpenAt;
        this.closeAt = scheduledCloseAt;
    }

    public void open(LocalDateTime openAt, LocalDateTime closeAt) {
        if (status != CampaignStatus.READY) {
            throw new InvalidCampaignStateTransitionException(status, CampaignStatus.OPEN);
        }
        this.status = CampaignStatus.OPEN;
        this.openAt = openAt;
        this.closeAt = closeAt;
    }

    public void close() {
        if (status != CampaignStatus.OPEN) {
            throw new InvalidCampaignStateTransitionException(status, CampaignStatus.CLOSED);
        }
        this.status = CampaignStatus.CLOSED;
    }

    // 재고 1장 차감. 호출 전 getRemainingStock() > 0 확인은 호출측(StockReservationStrategy) 책임 —
    // 여기서는 방어적으로만 막는다.
    public void decreaseStock() {
        if (remainingStock <= 0) {
            throw new IllegalStateException("remainingStock must be positive to decrease");
        }
        this.remainingStock--;
    }

    // Redis 상태 유실 대비 워밍업(04_아키텍처.txt 4.2절)에서 DB 실측 발급 건수 기준으로 재동기화할 때 사용
    public void restoreStock() {
        if (remainingStock >= totalStock) {
            throw new IllegalStateException("remainingStock cannot exceed totalStock");
        }
        this.remainingStock++;
    }

    // 혜택 배치는 reverse()/decreaseStock()을 거치지 않는 벌크 발급이라 발급 완료 후 remaining_stock을 직접 0으로 맞춰야 함
    // 안 맞추면 검증쿼리 카운터 불일치로 잡힘
    public void markFullyIssued() {
        this.remainingStock = 0;
    }

    // 04_아키텍처.txt 6절: min_membership_tier가 NULL이면 항상 true,
    // 아니면 tier.ordinal() >= minMembershipTier.ordinal()로 판정하는 순수 함수
    public boolean isEligible(MembershipTier tier) {
        if (minMembershipTier == null) {
            return true;
        }
        return tier.ordinal() >= minMembershipTier.ordinal();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getTotalStock() {
        return totalStock;
    }

    public int getRemainingStock() {
        return remainingStock;
    }

    public LocalDateTime getOpenAt() {
        return openAt;
    }

    public LocalDateTime getCloseAt() {
        return closeAt;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public MembershipTier getMinMembershipTier() {
        return minMembershipTier;
    }

    public CampaignType getCampaignType() {return campaignType;}

    public Long getVersion() {
        return version;
    }
}
