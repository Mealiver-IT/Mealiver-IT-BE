package com.mealiverit.entity.campaign;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
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

    // 2026-08-20 부하테스트(coupon_mixed_5k_x4.js) 실측: findByIdForUpdate() + 엔티티 갱신 방식은
    // 락을 애플리케이션 로직이 끝날 때까지(커밋 시점까지) 들고 있어서, hot row 경합 시 그 보유 시간만큼씩
    // 요청이 순서대로 쌓인다. "조건 확인 + 차감"을 단일 원자 UPDATE로 묶으면 락(InnoDB의 암묵적
    // 행 잠금)이 이 문장 실행 구간으로만 좁혀져 보유 시간이 크게 줄어든다 - PessimisticLockStockReservationStrategy가
    // 이 패턴으로 재고 확보/원복 둘 다 처리한다(Redis 게이트 여부와 무관, 순수 DB 최적화).
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Campaign c SET c.remainingStock = c.remainingStock - 1 "
            + "WHERE c.id = :campaignId AND c.remainingStock > 0")
    int decreaseStockIfAvailable(@Param("campaignId") Long campaignId);

    // compensateStockRollback()용 - reserve() 실패 이후 발급이 최종 무산됐을 때 재고를 원복한다.
    // 마찬가지로 findByIdForUpdate() 없이 단일 원자 UPDATE로 처리해 락 보유 시간을 줄인다.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Campaign c SET c.remainingStock = c.remainingStock + 1 "
            + "WHERE c.id = :campaignId AND c.remainingStock < c.totalStock")
    int increaseStockIfBelowTotal(@Param("campaignId") Long campaignId);

    // MembershipBenefitBatchJob의 합성 캠페인 재실행(idempotency) 판단용
    // 이름으로 기존 캠페인 조회
    Optional<Campaign> findByName(String name);

    // CampaignStockSnapshotReconciliationJob이 주기적으로 Redis 스냅샷을 재동기화할 대상 조회용.
    // CLOSED/READY 캠페인은 신규 발급 요청 자체가 안 들어오므로 대상에서 제외한다.
    List<Campaign> findByStatus(CampaignStatus status);

    // 2026-08-20 재고 샤딩 도입 이후 campaign.remaining_stock은 더 이상 재고 판단의 원본이 아니다
    // (CampaignStockShard 합계가 원본). 이 컬럼은 관리자 CRUD 화면/검증쿼리(b) 등 기존 코드가 계속
    // 읽을 수 있도록, 스냅샷 리스너/재동기화 잡이 샤드 합계를 사후에 복사해두는 표시용 값이다.
    @Modifying
    @Query("UPDATE Campaign c SET c.remainingStock = :value WHERE c.id = :campaignId")
    void setRemainingStock(@Param("campaignId") Long campaignId, @Param("value") int value);

    // CampaignScheduledOpenBatchJob이 자동 오픈 대상(예약 시간이 지난 READY 캠페인)을 찾을 때 사용
    // 새 칼럼 없이 기존 openAt을 "예정 시각"으로 재사용
    List<Campaign> findByStatusAndOpenAtLessThanEqual(CampaignStatus status, LocalDateTime now);
}
