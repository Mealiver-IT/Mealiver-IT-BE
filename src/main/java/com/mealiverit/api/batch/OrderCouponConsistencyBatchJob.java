package com.mealiverit.api.batch;

import com.mealiverit.api.coupon.service.CouponIssueService;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// 상태전이 중 장애 시 유효성 체크 대응: OrderService.cancelOrder()가 주문취소와
// 쿠폰 복귀를 독립된 트랜잭션으로 처리하므로(REQUIRES_NEW 커넥션 풀 고갈 문제로 분리, PR #59),
// 둘 사이에 장애가 끼면 "주문은 취소됐는데 쿠폰은 USED로 남는" 불일치가 생길 수 있다.
// 매일 이 불일치를 찾아 markReturnedToIssued()를 재호출해 자동 복구한다.
// requestId를 주문 ID 기반으로 고정해, 배치를 여러 번 돌려도 멱등하다.
@Component
public class OrderCouponConsistencyBatchJob {

    private static final Logger logger = LoggerFactory.getLogger(OrderCouponConsistencyBatchJob.class);

    private static final String SELECT_INCONSISTENT_SQL =
                    "SELECT o.id AS order_id, o.coupon_issue_id " +
                    "FROM orders o " +
                    "JOIN coupon_issue ci ON o.coupon_issue_id = ci.id " +
                    "WHERE o.status = 'CANCELED' AND ci.status = 'USED'";

    private final JdbcTemplate jdbcTemplate;
    private final CouponIssueService couponIssueService;

    public OrderCouponConsistencyBatchJob(JdbcTemplate jdbcTemplate, CouponIssueService couponIssueService) {
        this.jdbcTemplate = jdbcTemplate;
        this.couponIssueService = couponIssueService;
    }

    @Scheduled(cron = "0 0 4 * * *") //매일 새벽 4시 (만료 배치 3시와 겹치지 않게)
    @SchedulerLock(name = "orderCouponConsistencyBatchJob", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void reconcile() {
        LockAssert.assertLocked();
        run();
    }

    public Result run() {
        List<Map<String, Object>> targets = jdbcTemplate.queryForList(SELECT_INCONSISTENT_SQL);

        int recoveredCount = 0;
        for (Map<String, Object> row : targets) {
            Long orderId = ((Number) row.get("order_id")).longValue();
            Long couponIssueId = ((Number) row.get("coupon_issue_id")).longValue();
            try {
                couponIssueService.markReturnedToIssued(couponIssueId, "batch-recovery-order-" + orderId);
                recoveredCount++;
            } catch (Exception e) {
                logger.error("정합성 복구 실패: orderId={}, couponIssueId={}", orderId, couponIssueId, e);
            }
        }

        Result result = new Result(targets.size(), recoveredCount);
        logger.info("{}", result);
        return result;
    }

    public static final class Result {
        private final int targetCount;
        private final int recoveredCount;

        public Result(int targetCount, int recoveredCount) {
            this.targetCount = targetCount;
            this.recoveredCount = recoveredCount;
        }

        public int getTargetCount() {
            return targetCount;
        }

        public int getRecoveredCount() {
            return recoveredCount;
        }

        @Override
        public String toString() {
            return "OrderCouponConsistencyBatchJob done: targetCount=" + targetCount + ", recoveredCount=" + recoveredCount;
        }
    }
}
