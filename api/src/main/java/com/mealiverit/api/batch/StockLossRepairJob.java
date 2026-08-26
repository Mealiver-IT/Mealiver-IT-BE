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
// 않는다 - CampaignStockSnapshotReconciliationJob이 "정상 재고를 품절로 오판하는 방향"만
// 자가치유하는 것과 같은 원칙(안전한 방향만 자동화).
//
// 2026-08-26 알림 정책 재정리: 처음엔 초과 방향을 발견할 때마다(60초 주기) Slack으로 알렸는데,
// 자동 복구가 없는 방향이라 데이터를 직접 고치기 전까지 같은 불일치를 매번 다시 알려서 팀 Slack
// 채널에 스팸이 됐다. 그래서 검증 시점을 둘로 나눴다:
//   1) 주기 검증(repair(), OPEN/READY 캠페인 대상, 60초마다) - 캠페인이 아직 "핫한"(오픈 중인)
//      동안 재고 부족을 최대한 빨리 복구하려는 원래 목적은 유지하되, 결과는 로그로만 남기고
//      Slack은 보내지 않는다(같은 불일치가 반복 알림되는 걸 막기 위함).
//   2) 종료 시 최종 검증(checkOnClose(), CampaignClosedStockCheckListener가 캠페인이 CLOSED로
//      전환된 직후 1회만 호출) - 그 캠페인에 대해 다시는 검증이 돌지 않으므로(findStockMismatches()가
//      CLOSED를 대상에서 제외 - 아래 쿼리 주석 참고) 여기서 발견되는 불일치가 사실상 마지막
//      기회다. 그래서 이 경로에서만, 캠페인당 정확히 한 번 Slack으로 알린다.
//
// @Transactional을 안 건 이유: 이 클래스가 하는 쓰기는 stockReservationStrategy.rollback()
// 뿐인데, 그 내부(ShardedStockReservationStrategy)는 이미 REQUIRES_NEW로 샤드 하나하나를
// 즉시 커밋한다. 메서드에 트랜잭션을 걸어봐야 감쌀 쓰기가 없고, 오히려
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

    // 2026-08-26: 처음엔 5분 주기였는데, 이 프로젝트의 선착순 캠페인은 보통 수십 초~1분 안에
    // 재고가 소진된다(이번 세션 부하테스트 실측) - 5분이면 유실이 복구되기도 전에 캠페인의
    // "핫한" 시간대가 이미 다 지나가버려 복구된 재고를 받아갈 유저가 없을 수 있다. 평상시
    // 비용(불일치 없으면 집계 쿼리 1회)이 낮아서 짧게 잡아도 부담이 적다.
    @Scheduled(fixedDelay = 60000)
    @SchedulerLock(name = "stockLossRepairJob", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
    public void scheduledRepair() {
        LockAssert.assertLocked();
        repair();
    }

    // 주기 검증 - findStockMismatches()가 CLOSED 캠페인을 이미 걸러주므로 OPEN/READY만 대상이다.
    // 결과는 로그로만 남기고 Slack은 보내지 않는다(클래스 상단 주석 참고).
    public void repair() {
        List<StockMismatchProjection> mismatches = campaignRepository.findStockMismatches();
        for (StockMismatchProjection mismatch : mismatches) {
            handleMismatch(mismatch, false);
        }
        if (!mismatches.isEmpty()) {
            log.info("재고 유실 탐지·복구 완료: 대상 캠페인 {}건", mismatches.size());
        }
    }

    // 캠페인이 CLOSED로 전환된 직후 CampaignClosedStockCheckListener가 1회 호출하는 최종 검증.
    // 이후로는 이 캠페인이 주기 검증 대상에서 빠지므로, 여기서 발견되는 불일치를 Slack으로 알린다.
    // 불일치가 없으면(정상 종료) 아무것도 하지 않는다 - 모든 캠페인 종료마다 알리면 그 자체가
    // 새로운 스팸이 된다.
    public void checkOnClose(Long campaignId) {
        campaignRepository.findStockMismatch(campaignId)
                .ifPresent(mismatch -> handleMismatch(mismatch, true));
    }

    private void handleMismatch(StockMismatchProjection mismatch, boolean notifySlack) {
        int expectedRemaining = mismatch.getTotalStock() - mismatch.getIssuedCount();
        int deficit = expectedRemaining - mismatch.getShardRemaining();
        if (deficit > 0) {
            repairDeficit(mismatch.getCampaignId(), deficit, notifySlack);
        } else {
            alertExcess(mismatch, notifySlack);
        }
    }

    // deficit만큼 strategy.rollback()을 반복 호출한다 - 샤드별 capacity를 지키며 순회하는 로직을
    // ShardedStockReservationStrategy.rollback()이 이미 갖고 있어 그대로 재사용한다(같은 sweep
    // 로직을 여기서 세 번째로 베끼지 않기 위함).
    private void repairDeficit(Long campaignId, int deficit, boolean notifySlack) {
        for (int i = 0; i < deficit; i++) {
            stockReservationStrategy.rollback(campaignId);
        }
        log.info("재고 유실 복구: campaignId={}, 복구량={}", campaignId, deficit);
        if (notifySlack) {
            slackNotifier.send(new ConsistencyReport(
                    0L,
                    "StockLossRepairJob(캠페인 종료 - 재고 유실 자동복구됨)",
                    LocalDateTime.now(),
                    BatchStatus.COMPLETED,
                    Map.of(CheckType.COUNTER_MISMATCH, 1L),
                    List.of("campaignId=" + campaignId + ", repaired=" + deficit)));
        }
    }

    private void alertExcess(StockMismatchProjection mismatch, boolean notifySlack) {
        int excess = mismatch.getShardRemaining() - (mismatch.getTotalStock() - mismatch.getIssuedCount());
        log.error("재고 카운터 불일치(초과 방향) - 자동 복구 안 함, 수동 확인 필요: "
                        + "campaignId={}, totalStock={}, shardRemaining={}, issuedCount={}, excess={}",
                mismatch.getCampaignId(), mismatch.getTotalStock(), mismatch.getShardRemaining(),
                mismatch.getIssuedCount(), excess);
        if (notifySlack) {
            slackNotifier.send(new ConsistencyReport(
                    0L,
                    "StockLossRepairJob(재고 초과 의심 - 수동 확인 필요)",
                    LocalDateTime.now(),
                    BatchStatus.COMPLETED,
                    Map.of(CheckType.COUNTER_MISMATCH, 1L),
                    List.of("campaignId=" + mismatch.getCampaignId() + ", excess=" + excess)));
        }
    }
}
