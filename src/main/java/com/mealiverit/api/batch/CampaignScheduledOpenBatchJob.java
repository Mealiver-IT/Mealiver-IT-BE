package com.mealiverit.api.batch;

import com.mealiverit.api.campaign.event.CampaignStatusChangedEvent;
import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import com.mealiverit.api.campaign.CampaignStatus;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

// 캠페인 오픈시간 예약 - READY 상태 캠페인 중 예약 시각(openAt, 생성 시점에 미리 저장해둔 값)이 지난 것을 자동으로 OPEN 전환 - 새 컬럼 없이 기존 openAt을 재사용
// 선착순 오픈런 특성상 정확한 타이밍이 중요해서, 다른 재동기화 잡들의 15초 주기보다 훨씬 촘촘한 1초 주기로 확인
// 관리자가 예약 시각 전에 수동으로 PATCH .../status를 먼저 호출하면 그 순간 이미 OPEN이 되므로, 이 배치의 조회 조건(status=READY)에 더 이상 안 걸려 충돌이 안 남
// CampaignStatusChangedEvent를 그대로 발행해서 SSE 스트림(관리자/소비자)에도 자동 오픈이 실시간으로 반영 - CampaignAdminService.updateStatus()와 동일한 경로 재사용
@Component
public class CampaignScheduledOpenBatchJob {

    private static final Logger log = LoggerFactory.getLogger(CampaignScheduledOpenBatchJob.class);

    private final CampaignRepository campaignRepository;
    private final ApplicationEventPublisher eventPublisher;

    public CampaignScheduledOpenBatchJob(CampaignRepository campaignRepository, ApplicationEventPublisher eventPublisher) {
        this.campaignRepository = campaignRepository;
        this.eventPublisher = eventPublisher;
    }

    // CampaignStockSnapshotReconciliationJob과 동일한 이유로 스케줄 진입점과 실행 로직을 분리
    // -> self-invocation으로 인한 @Transactional 프록시 우회 방지 + 테스트에서 ShedLock 없이 run()을 단독으로 호출할 수 있게 하기 위함
    @Transactional
    @Scheduled(fixedRate = 1000)
    @SchedulerLock(name = "campaignScheduledOpenBatchJob", lockAtLeastFor = "PT0.5S", lockAtMostFor = "PT10S")
    public void scheduledRun() {
        LockAssert.assertLocked();
        run();
    }

    @Transactional
    public void run() {
        List<Campaign> dueCampaigns = campaignRepository.findByStatusAndOpenAtLessThanEqual(CampaignStatus.READY, LocalDateTime.now());
        for (Campaign campaign : dueCampaigns) {
            // getCloseAt()은 생성 시점에 미리 예약해둔 scheduledCloseAt(없으면 null=무기한)을 그대로 보존한다.
            campaign.open(campaign.getOpenAt(), campaign.getCloseAt());
            eventPublisher.publishEvent(new CampaignStatusChangedEvent(campaign.getId(), campaign.getStatus()));
            log.info("캠페인 예약 오픈 실행: campaign={}, openAt={}", campaign.getId(), campaign.getOpenAt());
        }
    }
}
