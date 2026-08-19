package com.mealiverit.api.campaign.service;

import com.mealiverit.api.campaign.dto.CampaignCreateRequest;
import com.mealiverit.api.campaign.dto.CampaignResponse;
import com.mealiverit.api.campaign.dto.CampaignStatusUpdateRequest;
import com.mealiverit.api.campaign.dto.CampaignStockResponse;
import com.mealiverit.api.common.exception.BusinessException;
import com.mealiverit.api.common.exception.ErrorCode;
import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.coupon.entity.Coupon;
import com.mealiverit.entity.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 관리자용 캠페인/쿠폰 CRUD(간단히) + 상태 수동 전환. 인증/권한은 이 프로젝트 평가범위 밖(일정과역할.txt 5절 제외 스택).
@Service
public class CampaignAdminService {

    private final CampaignRepository campaignRepository;
    private final CouponRepository couponRepository;

    public CampaignAdminService(CampaignRepository campaignRepository, CouponRepository couponRepository) {
        this.campaignRepository = campaignRepository;
        this.couponRepository = couponRepository;
    }

    @Transactional
    public CampaignResponse create(CampaignCreateRequest request) {
        Campaign campaign = campaignRepository.save(
                new Campaign(request.name(), request.totalStock(), request.minMembershipTier()));
        Coupon coupon = couponRepository.save(new Coupon(campaign.getId(), request.discountType(),
                request.discountValue(), request.minOrderAmount(), request.maxDiscountAmount(),
                request.validHours()));
        return CampaignResponse.of(campaign, coupon);
    }

    @Transactional(readOnly = true)
    public CampaignResponse getById(Long campaignId) {
        Campaign campaign = findCampaignOrThrow(campaignId);
        Coupon coupon = couponRepository.findByCampaignId(campaignId).orElse(null);
        return CampaignResponse.of(campaign, coupon);
    }

    // 선착순 잔여 수량 조회 - 발급 로직이 직접 건드리는 remainingStock을 그대로 조회만 함
    // 이 메소드 자체는 재고에 관여하지 않음
    @Transactional(readOnly = true)
    public CampaignStockResponse getStock(Long campaignId) {
        Campaign campaign = findCampaignOrThrow(campaignId);
        return CampaignStockResponse.from(campaign);
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
            case OPEN -> campaign.open(request.openAt() != null ? request.openAt() : LocalDateTime.now(),
                    request.closeAt());
            case CLOSED -> campaign.close();
            case READY -> throw new BusinessException(ErrorCode.CAMPAIGN_INVALID_STATE_TRANSITION);
        }
        Coupon coupon = couponRepository.findByCampaignId(campaignId).orElse(null);
        return CampaignResponse.of(campaign, coupon);
    }

    private Campaign findCampaignOrThrow(Long campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND));
    }
}
