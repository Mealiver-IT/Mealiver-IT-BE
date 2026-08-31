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
//
// 재개(resume) 관련: 이 러너는 별도 재개 로직이 필요 없다 - MembershipTierBatchJob.run()이
// "현재 orders 기준으로 전체 재산정"이라 원래부터 몇 번을 다시 돌려도 안전하다(멱등).
// membership_tier_log도 실제 전이(from_tier != to_tier)가 있을 때만 남기므로, 중간에 죽었다가
// 재실행해도 이미 올바르게 반영된 유저는 로그가 중복으로 쌓이지 않는다(MembershipTierBatchJob 주석 참고).
// 다만 처음부터 전체를 다시 스캔/UPDATE하므로 시간을 아끼는 효과는 없고, "다시 돌려도 결과가
// 틀어지지 않는다"는 안전성만 보장한다.
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
