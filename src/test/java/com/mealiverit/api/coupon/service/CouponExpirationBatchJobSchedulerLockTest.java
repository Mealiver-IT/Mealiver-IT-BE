package com.mealiverit.api.coupon.service;

import com.mealiverit.api.batch.CouponExpirationBatchJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Phase 2: CouponExpirationBatchJob 중복실행 방지 (ShedLock) 검증
// 배치 자체가 멱등적으로 짜여 있어 (이미 처리된 건 재처리 안 함) 결과 데이터마으로는 "실행이 몇 번 됐는지 구분이 안 됨
// 따라서 shedlock 테이블의 locked_at이 두 번째 호출 후에도 그대로인지 여부로 "스킵됐다"를 증명
// lockAtLeastFor=1분 안에서는 순차 호출이어도 두 번째는 락을 못 잡는다.
@SpringBootTest
class CouponExpirationBatchJobSchedulerLockTest {

    @Autowired
    private CouponExpirationBatchJob couponExpirationBatchJob;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 짧은_간격으로_두번_호출하면_두번째는_락에_막혀_스킵된다() {
        couponExpirationBatchJob.expireOverdueCoupons();
        LocalDateTime firstLockedAt = lockedAt();
        assertThat(firstLockedAt).as("첫 호출은 락을 잡아야 함").isNotNull();

        couponExpirationBatchJob.expireOverdueCoupons();
        LocalDateTime secondLockedAt = lockedAt();
        assertThat(secondLockedAt)
                .as("lockAtLeastFor(1분) 안이라 두 번째 호출은 락을 다시 못 잡고 스킵돼야 함")
                .isEqualTo(firstLockedAt);
    }

    private LocalDateTime lockedAt() {
        List<LocalDateTime> rows = jdbcTemplate.query(
                "SELECT locked_at FROM shedlock WHERE name = ?",
                (rs, rowNum) -> rs.getObject("locked_at", LocalDateTime.class),
                "couponExpirationBatchJob");
        return rows.isEmpty() ? null : rows.get(0);
    }
}
