package com.mealiverit.api.coupon.service;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// ShardedStockReservationStrategy에서 분리된 이유: 2026-08-22 실측(round-10, sys.innodb_lock_waits로
// 확인) - 배치 INSERT를 ShardedStockReservationStrategy.createShards()에서 그냥 JdbcTemplate으로
// 직접 실행했더니, 그 호출이 바깥 호출자(CouponIssuanceTransactionalOperations.reserveStock(),
// @Transactional)의 트랜잭션에 그대로 편승해버렸다 - 그 결과 새로 생성된 샤드 50개 행의 락이
// insertIgnore() 실행 즉시가 아니라 reserveStock() 전체가 끝날 때까지(커밋될 때까지) 안 풀렸다.
// 새 캠페인 첫 요청에 5,000명이 몰리는 상황에서 이 한 트랜잭션이 조금만 늦어져도, 뒤따르는
// 요청 전부가 50개 샤드 중 하나를 잡으려고 줄을 서다 InnoDB lock_wait_timeout(50초)까지
// 한꺼번에 밀렸다(pid 9778 트랜잭션 하나가 rows_modified=50인 채로 1분 넘게 안 풀리며 수십 개
// 세션을 block). 예전(건별 REQUIRES_NEW insertIgnore()) 방식은 삽입 즉시 커밋돼 이 문제가 없었다.
//
// 그렇다고 예전 방식(건마다 커넥션 재획득)으로 되돌리면 그 나름의 위험(커넥션 왕복 N회)이
// 되살아난다 - 그래서 배치 INSERT는 유지하되, REQUIRES_NEW로 별도 빈에 분리해 "커넥션 왕복은
// 1회, 그러나 그 1회짜리 트랜잭션은 바깥 트랜잭션과 무관하게 즉시 커밋"되게 한다. 같은 클래스
// 안에서 this.method() 자기호출로는 프록시를 안 타서 @Transactional이 무시된다(이 저장소에서
// 이미 한 번 실제로 겪은 문제 - CouponStateTransitionOperations, 재동기화 잡 self-invocation
// 버그 참고) - 그래서 반드시 별도 빈으로 분리해야 한다.
@Component
class CampaignStockShardBatchCreator {

    private static final String INSERT_SHARD_SQL = "INSERT IGNORE INTO campaign_stock_shard "
            + "(campaign_id, shard_index, remaining_stock, capacity) VALUES (?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    CampaignStockShardBatchCreator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void createAll(List<Object[]> batchArgs) {
        jdbcTemplate.batchUpdate(INSERT_SHARD_SQL, batchArgs);
    }
}
