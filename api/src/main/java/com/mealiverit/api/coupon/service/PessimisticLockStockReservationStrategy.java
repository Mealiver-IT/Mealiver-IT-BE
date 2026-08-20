package com.mealiverit.api.coupon.service;

import com.mealiverit.entity.campaign.CampaignRepository;
import org.springframework.stereotype.Component;

// V2 — DB 비관적 락 (03_버전사다리_실험설계.txt 4절). Phase 1 MVP 기본 전략.
// 2026-08-20 부하테스트(coupon_mixed_5k_x4.js) 실측 전까지는 findByIdForUpdate()로 캠페인 row를
// SELECT ... FOR UPDATE로 잠근 뒤 엔티티를 갱신하고 트랜잭션 커밋 시점까지 락을 들고 있었다 -
// 정확성은 보장되지만 락 보유 시간이 "애플리케이션 로직이 끝날 때까지"로 길어져, hot row 경합 시
// 그 시간만큼씩 요청이 순서대로 쌓였다(20,000 요청 순간 폭주에서 응답시간 최대 175초 관측).
// 지금은 "조건 확인 + 차감"을 단일 원자 UPDATE(CampaignRepository.decreaseStockIfAvailable)로
// 묶어서, InnoDB의 암묵적 행 잠금이 이 UPDATE 문 실행 구간으로만 좁혀지도록 바꿨다 - 여전히 DB
// 비관적 락(row lock) 기반이라는 성격 자체는 그대로이고(Redis 게이트 아님), 락을 오래 들고 있지
// 않게 됐을 뿐이다. eligibility/campaign-open 체크는 이 원자 UPDATE와 무관한 락 없는 조회로
// CouponIssuanceTransactionalOperations.reserveStock()에서 먼저 판정한다.
@Component
public class PessimisticLockStockReservationStrategy implements StockReservationStrategy {

    private final CampaignRepository campaignRepository;

    public PessimisticLockStockReservationStrategy(CampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    @Override
    public boolean reserve(Long campaignId) {
        return campaignRepository.decreaseStockIfAvailable(campaignId) > 0;
    }

    @Override
    public void rollback(Long campaignId) {
        // reserve()와 이 rollback()은 서로 다른 트랜잭션에서 호출된다(compensateStockRollback()
        // 참고) - 원자 UPDATE라 별도 조회/락 없이도 최신 remainingStock 기준으로 안전하게 원복된다.
        campaignRepository.increaseStockIfBelowTotal(campaignId);
    }
}
