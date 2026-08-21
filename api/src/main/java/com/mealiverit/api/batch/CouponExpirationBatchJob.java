package com.mealiverit.api.batch;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// FR-CPS-003, 04_아키텍처.txt 3절: coupon_issue.valid_until(발급 시점 스냅샷) 경과한 ISSUED 쿠폰을
// 매일 새벽 3시 EXPIRED로 전이한다. idx_ci_status_valid_until 인덱스로 대상을 찾는다.
//
// MembershipTierBatchJob과 동일하게 대용량(최종 300만 건 규모) 대비 JPA 엔티티 로딩 대신
// JdbcTemplate 청크 처리를 쓴다. SELECT ~ UPDATE 사이에 해당 쿠폰이 다른 요청으로 먼저
// USED/CANCELED 되는 경쟁 상태를 대비해, UPDATE는 WHERE status='ISSUED' 조건부로 걸고
// 실제로 영향받은 행(affected row)만 coupon_state_log에 기록한다 — 그래야 "로그엔 EXPIRED인데
// 실제 상태는 USED" 같은 로그/상태 불일치가 안 생긴다(FR-VRF 정합성 검증의 전제).
@Component
public class CouponExpirationBatchJob {

    private static final Logger log = LoggerFactory.getLogger(CouponExpirationBatchJob.class);

    private static final String SELECT_TARGET_IDS_SQL =
            "SELECT id FROM coupon_issue WHERE status = 'ISSUED' AND valid_until < ? ORDER BY id ";

    private static final String UPDATE_SQL =
            "UPDATE coupon_issue SET status = 'EXPIRED', expired_at = ?, version = version + 1 "
            + "WHERE id = ? AND status = 'ISSUED'";

    private static final String INSERT_LOG_SQL =
            "INSERT INTO coupon_state_log (coupon_issue_id, from_status, to_status, request_id, reason, created_at) "
            + "VALUES (?, 'ISSUED', 'EXPIRED', ?, 'SYSTEM_EXPIRY', ?)";

    private static final int BATCH_FLUSH_SIZE = 5000;

    private final JdbcTemplate jdbcTemplate;

    public CouponExpirationBatchJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "0 0 3 * * *") //매일 새벽 3시
    @SchedulerLock(name = "couponExpirationBatchJob", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void expireOverdueCoupons() {
        LockAssert.assertLocked();
        run(LocalDateTime.now());
    }

    public Result run(LocalDateTime now) {
        List<Long> targetIds = jdbcTemplate.query(SELECT_TARGET_IDS_SQL, (rs, rowNum) -> rs.getLong("id"), now);

        int expiredCount = 0;
        List<Long> chunk = new ArrayList<>(BATCH_FLUSH_SIZE);
        for (Long issueId : targetIds) {
            chunk.add(issueId);
            if (chunk.size() >= BATCH_FLUSH_SIZE) {
                expiredCount += flush(chunk, now);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            expiredCount += flush(chunk, now);
        }

        Result result = new Result(targetIds.size(), expiredCount);
        log.info("{}", result);
        return result;
    }

    //UPDATE 결과(영향받은 행 수)를 확인해, 실제로 전이된 건만 로그에 남긴다.
    private int flush(List<Long> issueIds, LocalDateTime now) {
        List<Object[]> updateArgs = issueIds.stream()
                .map(id -> new Object[] {now, id})
                .toList();
        int[] update = jdbcTemplate.batchUpdate(UPDATE_SQL, updateArgs);

        List<Object[]> logArgs = new ArrayList<>();
        int expiredCount = 0;
        for (int i = 0; i < update.length; i++) {
            if (update[i] > 0) {
                expiredCount++;
                logArgs.add(new Object[] {issueIds.get(i), UUID.randomUUID().toString(), now});
            }
        }
        if (!logArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_LOG_SQL, logArgs);
        }
        return expiredCount;
    }

    public static final class Result {
        private final int targetCount;
        private final int expiredCount;

        public Result(int targetCount, int expiredCount) {
            this.targetCount = targetCount;
            this.expiredCount = expiredCount;
        }

        public int getTargetCount() {
            return targetCount;
        }

        public int getExpiredCount() {
            return expiredCount;
        }

        @Override
        public String toString() {
            return "CouponExpirationBatchJob done: targetCount=" + targetCount + ", expiredCount=" + expiredCount;
        }
    }
}
