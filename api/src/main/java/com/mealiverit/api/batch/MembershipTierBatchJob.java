package com.mealiverit.api.batch;

import com.mealiverit.entity.user.MembershipTier;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 04_아키텍처.txt 6.2절, 05_시스템설계.txt 1.1(f): orders 완료건수(COMPLETED, paid_amount>=10000,
// 캘린더 월 윈도우) 집계로 users.membership_tier를 재산정한다.
//
// - 대상 유저는 LEFT JOIN + COALESCE로 전체 users를 스캔한다(주문 0건 유저도 반드시 포함) —
//   05_시스템설계.txt 1.1(f)가 INNER JOIN 금지를 명시한 이유와 동일: 0건 유저를 빼면
//   "등급은 SERGEANT인데 주문 0건"류 오염 케이스를 이 배치 스스로도 놓친다.
// - membership_tier_log는 실제 전이(from_tier != to_tier)가 있을 때만 남긴다(CouponStateLog와
//   동일 패턴, MembershipTierLog.java 주석 참고) — 동일 데이터로 재실행 시 로그가 0건 추가되는 것 자체가
//   결정론성(재실행해도 같은 결과)의 증거가 된다.
// - users.membership_tier/tier_calculated_at은 값이 같더라도 매 실행마다 갱신한다 —
//   tier_calculated_at의 의미가 "값이 바뀐 시각"이 아니라 "마지막 배치 실행(재산정) 시각"이기 때문
//   (User.java 주석 참고).
//
// MembershipTierSeedRunner(더미데이터 1회성 시딩)도 이 run()을 그대로 호출한다 — 별도 랜덤 배정 로직 없음.
@Component
public class MembershipTierBatchJob {

    private static final String SELECT_SQL =
        "SELECT u.id AS user_id, u.membership_tier AS current_tier, "
            + "COALESCE(o.order_count, 0) AS order_count "
            + "FROM users u "
            + "LEFT JOIN ( "
            + "  SELECT user_id, COUNT(*) AS order_count "
            + "  FROM orders "
            + "  WHERE status = 'COMPLETED' AND paid_amount >= 10000 "
            + "    AND completed_at >= ? AND completed_at < ? "
            + "  GROUP BY user_id "
            + ") o ON o.user_id = u.id "
            + "ORDER BY u.id";

    private static final String UPDATE_USER_SQL =
        "UPDATE users SET membership_tier = ?, tier_calculated_at = ? WHERE id = ?";

    private static final String INSERT_LOG_SQL =
        "INSERT INTO membership_tier_log (user_id, from_tier, to_tier, order_count, calculated_at) "
            + "VALUES (?, ?, ?, ?, ?)";

    private static final int BATCH_FLUSH_SIZE = 5_000;

    private final JdbcTemplate jdbcTemplate;

    public MembershipTierBatchJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 04_아키텍처.txt 6.2절: 매월 1일 새벽, 직전 캘린더 월 실적으로 재산정
    @Scheduled(cron = "0 0 2 1 * *")
    public void runMonthly() {
        run(YearMonth.now().minusMonths(1));
    }

    public Result run(YearMonth targetMonth) {
        LocalDateTime monthStart = targetMonth.atDay(1).atStartOfDay();
        LocalDateTime monthEndExclusive = targetMonth.plusMonths(1).atDay(1).atStartOfDay();
        LocalDateTime runAt = LocalDateTime.now();

        List<UserTierRow> rows = jdbcTemplate.query(
            SELECT_SQL,
            ps -> {
                ps.setObject(1, monthStart);
                ps.setObject(2, monthEndExclusive);
            },
            rowMapper()
        );

        List<Object[]> updateBatch = new ArrayList<>(BATCH_FLUSH_SIZE);
        List<Object[]> logBatch = new ArrayList<>(BATCH_FLUSH_SIZE);
        long[] tierCounts = new long[MembershipTier.values().length];
        long changedCount = 0;

        for (UserTierRow row : rows) {
            MembershipTier expectedTier = MembershipTierCalculator.fromCompletedOrderCount(row.orderCount);
            tierCounts[expectedTier.ordinal()]++;

            updateBatch.add(new Object[]{expectedTier.name(), runAt, row.userId});
            if (updateBatch.size() >= BATCH_FLUSH_SIZE) {
                jdbcTemplate.batchUpdate(UPDATE_USER_SQL, updateBatch);
                updateBatch.clear();
            }

            if (row.currentTier != expectedTier) {
                changedCount++;
                logBatch.add(new Object[]{
                    row.userId, row.currentTier.name(), expectedTier.name(), row.orderCount, runAt
                });
                if (logBatch.size() >= BATCH_FLUSH_SIZE) {
                    jdbcTemplate.batchUpdate(INSERT_LOG_SQL, logBatch);
                    logBatch.clear();
                }
            }
        }
        if (!updateBatch.isEmpty()) {
            jdbcTemplate.batchUpdate(UPDATE_USER_SQL, updateBatch);
        }
        if (!logBatch.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_LOG_SQL, logBatch);
        }

        Result result = new Result(targetMonth, rows.size(), changedCount, tierCounts);
        System.out.println(result);
        return result;
    }

    private RowMapper<UserTierRow> rowMapper() {
        return (rs, rowNum) -> new UserTierRow(
            rs.getLong("user_id"),
            MembershipTier.valueOf(rs.getString("current_tier")),
            rs.getInt("order_count")
        );
    }

    private static final class UserTierRow {
        private final long userId;
        private final MembershipTier currentTier;
        private final int orderCount;

        private UserTierRow(long userId, MembershipTier currentTier, int orderCount) {
            this.userId = userId;
            this.currentTier = currentTier;
            this.orderCount = orderCount;
        }
    }

    public static final class Result {
        private final YearMonth targetMonth;
        private final int totalUsers;
        private final long changedCount;
        private final long[] tierCounts;

        private Result(YearMonth targetMonth, int totalUsers, long changedCount, long[] tierCounts) {
            this.targetMonth = targetMonth;
            this.totalUsers = totalUsers;
            this.changedCount = changedCount;
            this.tierCounts = tierCounts;
        }

        public YearMonth getTargetMonth() {
            return targetMonth;
        }

        public int getTotalUsers() {
            return totalUsers;
        }

        public long getChangedCount() {
            return changedCount;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("MembershipTierBatchJob done: targetMonth=").append(targetMonth)
                .append(", totalUsers=").append(totalUsers)
                .append(", changed=").append(changedCount);
            MembershipTier[] tiers = MembershipTier.values();
            for (int i = 0; i < tiers.length; i++) {
                sb.append(", ").append(tiers[i]).append("=").append(tierCounts[i]);
            }
            return sb.toString();
        }
    }
}
