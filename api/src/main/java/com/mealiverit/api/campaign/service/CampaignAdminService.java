package com.mealiverit.api.campaign.service;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.campaign.dto.*;
import com.mealiverit.api.campaign.event.CampaignCreatedEvent;
import com.mealiverit.api.campaign.event.CampaignStatusChangedEvent;
import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.campaign.CampaignStockShardRepository;
import com.mealiverit.entity.campaign.CampaignType;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.repository.CouponIssueRepository;
import com.mealiverit.entity.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자용 캠페인/쿠폰 CRUD(간단히) + 상태 수동 전환. 인증/권한은 이 프로젝트 평가범위 밖(일정과역할.txt 5절 제외 스택).
@Service
public class CampaignAdminService {

    private final CampaignRepository campaignRepository;
    private final CouponRepository couponRepository;
    private final CampaignStockCache campaignStockCache;
    private final CouponIssueRepository couponIssueRepository;
    private final CampaignStockShardRepository campaignStockShardRepository;
    private final ApplicationEventPublisher eventPublisher;

    public CampaignAdminService(CampaignRepository campaignRepository, CouponRepository couponRepository,
                                CampaignStockCache campaignStockCache, CouponIssueRepository couponIssueRepository,
                                CampaignStockShardRepository campaignStockShardRepository, ApplicationEventPublisher eventPublisher) {
        this.campaignRepository = campaignRepository;
        this.couponRepository = couponRepository;
        this.campaignStockCache = campaignStockCache;
        this.couponIssueRepository = couponIssueRepository;
        this.campaignStockShardRepository = campaignStockShardRepository;
        this.eventPublisher = eventPublisher;
    }

    // 2026-08-26: 재고 샤드는 원래 이 캠페인의 첫 reserve()/rollback() 호출 시점에 지연 생성됐다
    // (ShardedStockReservationStrategy.ensureShardsExist 참고) - 샤딩 도입 이전부터 있던 캠페인도
    // 별도 백필 없이 자동으로 채워지게 하려는 이유였는데, 신규 캠페인은 생성 시점에 totalStock이
    // 이미 확정돼 있고(생성 후 수정 API도 없음) 그 마이그레이션 배경이 더 이상 해당 없다. 그래서
    // 생성 성공 시 이 이벤트를 발행해 커밋 직후 바로 샤드를 만든다(CampaignShardInitListener) -
    // 오픈 직후 첫 폭주 트래픽이 "누가 최초로 샤드를 만드는가"를 두고 경쟁할 일 자체가 없어진다.
    // ensureShardsExist()는 멱등이라 이 이벤트가 처리되기 전에 실제 reserve()가 먼저 들어와도
    // (이론상으로만 가능 - OPEN 전환은 별도 관리자 액션/스케줄 잡이 필요해 시간 여유가 있다)
    // 안전하게 그쪽에서 지연 생성 경로를 그대로 타면 된다.
    @Transactional
    public CampaignResponse create(CampaignCreateRequest request) {
        Campaign campaign = campaignRepository.save(
                new Campaign(request.name(), request.totalStock(), request.minMembershipTier(),
                        CampaignType.FCFS, request.scheduledOpenAt(), request.scheduledCloseAt()));
        Coupon coupon = couponRepository.save(new Coupon(campaign.getId(), request.discountType(),
                request.discountValue(), request.minOrderAmount(), request.maxDiscountAmount(),
                request.validHours()));
        // 커밋 성공 시에만 샤드를 만들어야 하므로 이벤트 발행만 하고, 실제 생성은
        // CampaignShardInitListener(AFTER_COMMIT)에 맡긴다 - create()가 실패해서 롤백되면
        // 이 이벤트도 자동으로 폐기된다.
        eventPublisher.publishEvent(new CampaignCreatedEvent(campaign.getId()));
        return CampaignResponse.of(campaign, coupon);
    }

    @Transactional(readOnly = true)
    public CampaignResponse getById(Long campaignId) {
        Campaign campaign = findCampaignOrThrow(campaignId);
        Coupon coupon = couponRepository.findByCampaignId(campaignId).orElse(null);
        return CampaignResponse.of(campaign, coupon);
    }

    // 선착순 잔여 수량 조회 - 발급 로직이 직접 건드리는 remainingStock을 그대로 조회만 함
    // 이 메소드 자체는 재고에 관여하지 않음. 실시간 대시보드가 이 API를 자주 폴링해도 발급
    // 트랜잭션과 DB 커넥션을 다투지 않도록 Redis 스냅샷을 우선 사용하고, 캐시 미스일 때만 DB로 폴백한다.
    @Transactional(readOnly = true)
    public CampaignStockResponse getStock(Long campaignId) {
        Campaign campaign = findCampaignOrThrow(campaignId);
        Integer cachedRemainingStock = campaignStockCache.getSnapshot(campaignId);
        return CampaignStockResponse.of(campaign, cachedRemainingStock);
    }

    // 선착순 발급 현황 통계 조회 - 발급 건수/잔여재고만 반환
    @Transactional(readOnly = true)
    public CampaignStatsResponse getStats(Long campaignId) {
        Campaign campaign = findCampaignOrThrow(campaignId);
        long issuedCount = couponIssueRepository.countByCampaignId(campaignId);
        return CampaignStatsResponse.of(campaign, issuedCount);
    }

    @Transactional(readOnly = true)
    public List<CampaignResponse> list() {
        List<Campaign> campaigns = campaignRepository.findAll();
        List<Long> campaignIds = campaigns.stream().map(Campaign::getId).toList();
        Map<Long, Coupon> couponByCampaignId = couponRepository.findByCampaignIdIn(campaignIds).stream()
                .collect(Collectors.toMap(Coupon::getCampaignId, Function.identity()));
        return campaigns.stream()
                .map(campaign -> CampaignResponse.of(campaign, couponByCampaignId.get(campaign.getId())))
                .toList();
    }

    @Transactional
    public CampaignResponse updateStatus(Long campaignId, CampaignStatusUpdateRequest request) {
        Campaign campaign = findCampaignOrThrow(campaignId);
        switch (request.status()) {
            // closeAt을 요청에서 안 주면(FE의 "지금 수동 오픈" 버튼처럼) 생성 시점에 미리 예약해둔
            // closeAt(scheduledCloseAt)을 덮어쓰지 않고 그대로 유지한다.
            case OPEN -> campaign.open(request.openAt() != null ? request.openAt() : LocalDateTime.now(),
                    request.closeAt() != null ? request.closeAt() : campaign.getCloseAt());
            case CLOSED -> campaign.close();
            case READY -> throw new BusinessException(ErrorCode.CAMPAIGN_INVALID_STATE_TRANSITION);
        }

        // 커밋 성공 시에만 SSE 구독자에게 알려야 하므로 이벤트 발생만 하고, 실제 전송은 CampaignStatusChangeListener(AFTER_COMMIT)에 맡김
        // 낙관적 락 충돌로 이 트랜잭션이 롤백되면 이 이벤트도 자동으로 폐기됨
        eventPublisher.publishEvent(new CampaignStatusChangedEvent(campaignId, campaign.getStatus()));
        Coupon coupon = couponRepository.findByCampaignId(campaignId).orElse(null);
        return CampaignResponse.of(campaign, coupon);
    }

    // 캠페인 삭제(하드 삭제) - 이미 쿠폰이 발급된 캠페인은 정합성 검증 배치가 참조하는 데이터라 삭제를 막음
    // FK 제약 순서상 campaign_stock_shard -> coupon -> campaign 순으로 지움
    @Transactional
    public void delete(Long campaignId) {
        Campaign campaign = findCampaignOrThrow(campaignId);
        if (couponIssueRepository.countByCampaignId(campaignId) > 0) {
            throw new BusinessException(ErrorCode.CAMPAIGN_HAS_ISSUED_COUPONS);
        }
        campaignStockShardRepository.deleteByCampaignId(campaignId);
        couponRepository.findByCampaignId(campaignId).ifPresent(couponRepository::delete);
        campaignRepository.delete(campaign);
    }

    private Campaign findCampaignOrThrow(Long campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND));
    }
}
