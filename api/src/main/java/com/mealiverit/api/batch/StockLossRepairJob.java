package com.mealiverit.api.batch;

import com.mealiverit.api.coupon.service.StockReservationStrategy;
import com.mealiverit.api.verification.report.CheckType;
import com.mealiverit.api.verification.report.ConsistencyReport;
import com.mealiverit.api.verification.report.SlackNotifier;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.campaign.StockMismatchProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// "발급 실패 재처리" 요청으로 설계됨(2026-08-24). 클라이언트가 실패로 인식했는데 서버는 성공한
// 경우는 이미 findByIdempotencyKey()로 처리되고, "진짜 실패한 요청을 나중에 재발급"은 FCFS
// 공정성에 안 맞아 범위에서 제외했다 - 대신 CouponIssuanceService.compensateStockRollbackSafely()의
// 주석이 이미 인정하는 구멍(재고 차감 후 발급도 실패하고 그 롤백 자체도 실패하면 - HikariCP
// 풀 고갈 등 - 아무도 못 받은 재고가 영구히 사라짐, ERROR 로그 한 줄만 남고 자동 복구가 없었음)을
// 탐지·복구하는 배치로 구현한다.
//
// sql/verification/b_counter_mismatch.sql과 같은 불변식(total_stock = 샤드 합계 + 발급 건수)을
// 쓰되, 방향을 반드시 구분한다: 재고가 "부족한" 방향(샤드 합계+발급건수 < total_stock)만
// 안전하게 자동 복구 가능하다 - 진짜 발급된 적 없는 재고를 되돌리는 것뿐이라 초과발급 위험이
// 없다. 반대 방향(재고가 "남는" 쪽)은 다른 종류의 버그(중복 복원 등)일 수 있어 자동으로 만지지
// 않고 Slack 알림만 보낸다 - CampaignStockSnapshotReconciliationJob이 "정상 재고를 품절로
// 오판하는 방향"만 자가치유하는 것과 같은 원칙(안전한 방향만 자동화).
//
// @Transactional을 안 건 이유: 이 메서드가 하는 쓰기는 stockReservationStrategy.rollback()
// 뿐인데, 그 내부(ShardedStockReservationStrategy)는 이미 REQUIRES_NEW로 샤드 하나하나를
// 즉시 커밋한다. 이 메서드 자체에 트랜잭션을 걸어봐야 감쌀 쓰기가 없고, 오히려
// CampaignStockSnapshotReconciliationJob에서 실제로 겪었던 self-invocation 문제(내부 메서드
// 호출로 프록시를 안 타서 @Transactional이 무시되는 것)를 반복할 여지만 늘어난다.
@Component
public class StockLossRepairJob {

    private static final Logger log = LoggerFactory.getLogger(StockLossRepairJob.class);

    private final CampaignRepository campaignRepository;
    private final StockReservationStrategy stockReservationStrategy;
    private final SlackNotifier slackNotifier;

    public StockLossRepairJob(CampaignRepository campaignRepository,
                               StockReservationStrategy stockReservationStrategy,
                               SlackNotifier slackNotifier) {
        this.campaignRepository = campaignRepository;
        this.stockReservationStrategy = stockReservationStrategy;
        this.slackNotifier = slackNotifier;
    }

    @Scheduled(fixedDelay = 300000)
    @SchedulerLock(name = "stockLossRepairJob", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
    public void scheduledRepair() {
        LockAssert.assertLocked();
        repair();
    }

    public void repair() {
        List<StockMismatchProjection> mismatches = campaignRepository.findStockMismatches();
        for (StockMismatchProjection mismatch : mismatches) {
            int expectedRemaining = mismatch.getTotalStock() - mismatch.getIssuedCount();
            int deficit = expectedRemaining - mismatch.getShardRemaining();
            if (deficit > 0) {
                repairDeficit(mismatch.getCampaignId(), deficit);
            } else {
                alertExcess(mismatch);
            }
        }
        if (!mismatches.isEmpty()) {
            log.info("재고 유실 탐지·복구 완료: 대상 캠페인 {}건", mismatches.size());
        }
    }

    // deficit만큼 strategy.rollback()을 반복 호출한다 - 샤드별 capacity를 지키며 순회하는 로직을
    // ShardedStockReservationStrategy.rollback()이 이미 갖고 있어 그대로 재사용한다(같은 sweep
    // 로직을 여기서 세 번째로 베끼지 않기 위함).
    private void repairDeficit(Long campaignId, int deficit) {
        for (int i = 0; i < deficit; i++) {
            stockReservationStrategy.rollback(campaignId);
        }
        log.info("재고 유실 복구: campaignId={}, 복구량={}", campaignId, deficit);
    }

    private void alertExcess(StockMismatchProjection mismatch) {
        int excess = mismatch.getShardRemaining() - (mismatch.getTotalStock() - mismatch.getIssuedCount());
        log.error("재고 카운터 불일치(초과 방향) - 자동 복구 안 함, 수동 확인 필요: "
                        + "campaignId={}, totalStock={}, shardRemaining={}, issuedCount={}, excess={}",
                mismatch.getCampaignId(), mismatch.getTotalStock(), mismatch.getShardRemaining(),
                mismatch.getIssuedCount(), excess);
        slackNotifier.send(new ConsistencyReport(
                0L,
                "StockLossRepairJob(재고 초과 의심 - 수동 확인 필요)",
                LocalDateTime.now(),
                BatchStatus.COMPLETED,
                Map.of(CheckType.COUNTER_MISMATCH, 1L),
                List.of("campaignId=" + mismatch.getCampaignId() + ", excess=" + excess)));
    }
}
