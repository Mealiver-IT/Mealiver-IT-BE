package com.mealiverit.api.seed;

import com.mealiverit.api.batch.MembershipTierBatchJob;
import java.time.YearMonth;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 일정과역할 Phase1: "MembershipTierBatchJob을 실제로 1회 실행해서 user.membership_tier 산출
// (별도 랜덤 배정 아님 — 배치 로직 그대로 재사용)". 이 러너는 새 로직을 만들지 않고
// MembershipTierBatchJob.run()을 그대로 1회 호출만 한다.
// OrderSeedRunner 다음에 실행되어야 하므로(orders가 있어야 집계할 게 있음) @Order로 순서를 뒤로 둔다.
@Component
@Order(20)
@ConditionalOnProperty(name = "seed.membershipTier.enabled", havingValue = "true")
public class MembershipTierSeedRunner implements CommandLineRunner {

    private final MembershipTierBatchJob batchJob;

    public MembershipTierSeedRunner(MembershipTierBatchJob batchJob) {
        this.batchJob = batchJob;
    }

    @Override
    public void run(String... args) {
        YearMonth targetMonth = SeedTargetMonth.resolve();
        batchJob.run(targetMonth);
    }
}
