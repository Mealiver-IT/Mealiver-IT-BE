package com.mealiverit.api.coupon.service;

import com.mealiverit.api.campaign.repository.CampaignRepository;

// V2 — DB 비관적 락, 원자 UPDATE 버전 (03_버전사다리_실험설계.txt 4절). 2026-08-20 재고 샤딩
// (ShardedStockReservationStrategy) 도입으로 활성 전략 자리에서는 물러났다 - campaign row 하나로
// 20,000건이 몰리는 처리량 한계 자체는 락 보유시간을 줄여도 안 풀렸기 때문(coupon_mixed_5k_x4.js
// 재측정으로 확인). "버전 사다리" 비교 대상으로 코드는 남겨두되 @Component를 제거해 더는
// 자동 주입되지 않는다 - 재활성화하려면 ShardedStockReservationStrategy의 @Component를
// 내리고 이쪽에 다시 붙이면 된다.
//
// 이 클래스 자체의 이력: 원래는 findByIdForUpdate()로 캠페인 row를 SELECT ... FOR UPDATE로 잠근 뒤
// 엔티티를 갱신하고 트랜잭션 커밋 시점까지 락을 들고 있었다 - 정확성은 보장되지만 락 보유 시간이
// "애플리케이션 로직이 끝날 때까지"로 길어져, hot row 경합 시 그 시간만큼씩 요청이 순서대로
// 쌓였다(20,000 요청 순간 폭주에서 응답시간 최대 175초 관측). "조건 확인 + 차감"을 단일 원자
// UPDATE(CampaignRepository.decreaseStockIfAvailable)로 묶어서 락 보유 구간을 그 UPDATE 문
// 실행 시간으로만 좁힌 게 이 버전이다.
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
