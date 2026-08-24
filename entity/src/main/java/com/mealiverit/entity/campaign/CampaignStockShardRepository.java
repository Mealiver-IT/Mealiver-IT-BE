package com.mealiverit.entity.campaign;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface CampaignStockShardRepository extends JpaRepository<CampaignStockShard, Long> {

    boolean existsByCampaignId(Long campaignId);

    // 스냅샷 리스너/재동기화 잡이 Redis·campaign.remaining_stock에 복사해둘 "진짜" 값을 구할 때 사용.
    // 샤드가 아직 없는 캠페인(지연 생성 전)이면 0이 아니라 null이 나올 수 있어 COALESCE로 방어.
    @Query("SELECT COALESCE(SUM(s.remainingStock), 0) FROM CampaignStockShard s WHERE s.campaignId = :campaignId")
    int sumRemainingStock(@Param("campaignId") Long campaignId);

    // REQUIRES_NEW인 이유: ShardedStockReservationStrategy.reserve()는 특정 샤드가 소진됐으면
    // 다음 샤드로 폴백한다 - 이 시도들을 전부 호출부(CouponIssuanceTransactionalOperations)의
    // 큰 트랜잭션 하나로 묶으면, 방문했지만 실패한 샤드 row의 잠금까지 트랜잭션이 끝날 때까지
    // 계속 들고 있게 된다. 서로 다른 요청이 서로 다른 순서로 여러 샤드를 방문하면 이 잠금들이
    // 교차하며 데드락이 난다(100 동시요청 테스트로 실측). 시도 하나하나를 즉시 커밋/해제되는
    // 독립 트랜잭션으로 만들어야 락을 오래 안 들고 있는 샤딩의 목적에도 맞다.
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE CampaignStockShard s SET s.remainingStock = s.remainingStock - 1 "
            + "WHERE s.campaignId = :campaignId AND s.shardIndex = :shardIndex AND s.remainingStock > 0")
    int decreaseIfAvailable(@Param("campaignId") Long campaignId, @Param("shardIndex") int shardIndex);

    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE CampaignStockShard s SET s.remainingStock = s.remainingStock + 1 "
            + "WHERE s.campaignId = :campaignId AND s.shardIndex = :shardIndex AND s.remainingStock < s.capacity")
    int increaseIfBelowCapacity(@Param("campaignId") Long campaignId, @Param("shardIndex") int shardIndex);

    // 샤드 지연 생성(배치 INSERT)은 ShardedStockReservationStrategy가 JdbcTemplate으로 직접
    // 처리한다 - 커넥션 왕복을 샤드 수만큼이 아니라 1회로 묶기 위해 이 리포지토리를 안 거친다.
}
