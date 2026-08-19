package com.mealiverit.entity.campaign;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    // 전략 (a) 비관적 락 — MVP 재고 차감 (04_아키텍처.txt 4.1절 V1.0, 5.1절)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Campaign c WHERE c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") Long id);

    // Redis 상태 유실 대비 총량 백스톱 (04_아키텍처.txt 4.2절).
    // Redis 게이트를 통과한 요청만 여기 도달하므로 hot row 경합 없이 항상 안전한 단일 원자 UPDATE.
    @Modifying
    @Query("UPDATE Campaign c SET c.remainingStock = c.remainingStock - 1 "
            + "WHERE c.id = :campaignId AND c.remainingStock > 0")
    int decreaseStockIfAvailable(@Param("campaignId") Long campaignId);

    // MembershipBenefitBatchJob의 합성 캠페인 재실행(idempotency) 판단용
    // 이름으로 기존 캠페인 조회
    Optional<Campaign> findByName(String name);

    // CampaignStockSnapshotReconciliationJob이 주기적으로 Redis 스냅샷을 재동기화할 대상 조회용.
    // CLOSED/READY 캠페인은 신규 발급 요청 자체가 안 들어오므로 대상에서 제외한다.
    List<Campaign> findByStatus(CampaignStatus status);
}
