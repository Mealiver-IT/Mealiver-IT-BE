package com.mealiverit.api.campaign.repository;

import com.mealiverit.api.campaign.CampaignStatus;
import com.mealiverit.api.campaign.StockMismatchProjection;
import com.mealiverit.api.campaign.entity.Campaign;
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

    // StockLossRepairJob.repair()(60초 주기)이 재고 유실(compensateStockRollback() 자체가
    // 실패하는 경우, HikariCP 풀 고갈 등) 여부를 찾을 때 사용. sql/verification/b_counter_mismatch.sql과
    // 같은 불변식(total_stock = 샤드 합계 + 발급 건수)을 검사하되, 캠페인마다 따로 조회하는 대신
    // 불일치하는 캠페인만 한 번의 집계 쿼리로 가져온다(CampaignStockSnapshotReconciliationJob처럼
    // 캠페인 수만큼 쿼리를 반복하는 N+1 패턴을 피하기 위함). LEFT JOIN 필수 - INNER JOIN이면 발급
    // 이력이 0건이거나 샤드가 아직 지연 생성 안 된 캠페인이 통째로 빠진다.
    //
    // 2026-08-26: CLOSED 캠페인은 대상에서 제외한다. 캠페인이 CLOSED로 전환되는 순간
    // StockLossRepairJob.checkOnClose()가 findStockMismatch()로 그 캠페인만 따로 마지막 검증을
    // 하므로(CampaignClosedStockCheckListener 참고), 이미 끝난 캠페인을 여기서 계속 반복
    // 검사하면 아무도 다시 못 받을 재고에 대해 매 60초마다 같은 로그만 반복해서 남기는
    // 낭비다(이전엔 Slack 알림까지 반복돼서 스팸이 됐던 지점).
    @Query(value = "SELECT c.id AS campaignId, c.total_stock AS totalStock, "
            + "       COALESCE(shard.remaining_stock, c.remaining_stock) AS shardRemaining, "
            + "       COALESCE(actual.issued_count, 0) AS issuedCount "
            + "FROM campaign c "
            + "LEFT JOIN (SELECT campaign_id, SUM(remaining_stock) AS remaining_stock "
            + "           FROM campaign_stock_shard GROUP BY campaign_id) shard ON shard.campaign_id = c.id "
            + "LEFT JOIN (SELECT campaign_id, COUNT(*) AS issued_count "
            + "           FROM coupon_issue GROUP BY campaign_id) actual ON actual.campaign_id = c.id "
            + "WHERE c.status <> 'CLOSED' "
            + "  AND c.total_stock <> COALESCE(shard.remaining_stock, c.remaining_stock) "
            + "                       + COALESCE(actual.issued_count, 0)",
            nativeQuery = true)
    List<StockMismatchProjection> findStockMismatches();

    // StockLossRepairJob.checkOnClose()가 캠페인이 CLOSED로 전환된 직후 1회 최종 검증할 때 사용.
    // findStockMismatches()와 같은 불변식이지만, 이미 CLOSED인 그 캠페인 자신을 검사해야 하므로
    // status 필터 없이 campaignId로만 좁힌다(쿼리 본문이 위와 거의 같아 중복이지만, 네이티브
    // 쿼리라 Spring Data 리포지토리 메서드끼리 조건절을 공유할 마땅한 방법이 없다).
    @Query(value = "SELECT c.id AS campaignId, c.total_stock AS totalStock, "
            + "       COALESCE(shard.remaining_stock, c.remaining_stock) AS shardRemaining, "
            + "       COALESCE(actual.issued_count, 0) AS issuedCount "
            + "FROM campaign c "
            + "LEFT JOIN (SELECT campaign_id, SUM(remaining_stock) AS remaining_stock "
            + "           FROM campaign_stock_shard GROUP BY campaign_id) shard ON shard.campaign_id = c.id "
            + "LEFT JOIN (SELECT campaign_id, COUNT(*) AS issued_count "
            + "           FROM coupon_issue GROUP BY campaign_id) actual ON actual.campaign_id = c.id "
            + "WHERE c.id = :campaignId "
            + "  AND c.total_stock <> COALESCE(shard.remaining_stock, c.remaining_stock) "
            + "                       + COALESCE(actual.issued_count, 0)",
            nativeQuery = true)
    Optional<StockMismatchProjection> findStockMismatch(@Param("campaignId") Long campaignId);
}
