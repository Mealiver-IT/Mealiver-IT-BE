package com.mealiverit.api.campaign.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealiverit.api.campaign.dto.CampaignCreateRequest;
import com.mealiverit.api.campaign.dto.CampaignResponse;
import com.mealiverit.api.campaign.service.CampaignAdminService;
import com.mealiverit.entity.campaign.CampaignStockShardRepository;
import com.mealiverit.entity.coupon.DiscountType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 2026-08-26: 재고 샤드를 캠페인 생성 시점에 바로 만들도록 바꾼 변경(ShardedStockReservationStrategy
// 상단 주석 참고 - 예전엔 첫 reserve()/rollback() 호출 시점까지 지연 생성했음) 검증.
//
// 클래스 레벨에 @Transactional을 붙이면 안 된다 - 붙이면 각 테스트 종료 시 롤백되어 create()가
// 실제로 커밋되지 않고, CampaignCreatedEvent도 AFTER_COMMIT이라 영원히 발행되지 않는다
// (CouponIssuedNotificationTest와 동일한 이유).
@SpringBootTest
class CampaignShardInitListenerTest {

    @Autowired
    private CampaignAdminService campaignAdminService;
    @Autowired
    private CampaignStockShardRepository campaignStockShardRepository;

    @Test
    void 캠페인_생성_직후_별도_reserve_호출_없이도_샤드가_생성된다() throws InterruptedException {
        CampaignResponse response = campaignAdminService.create(new CampaignCreateRequest(
                "샤드 즉시생성 테스트 캠페인", 100, null,
                DiscountType.FIXED, BigDecimal.valueOf(1000), null, null, 24));
        Long campaignId = response.id();

        // AFTER_COMMIT + @Async라 약간의 지연이 있을 수 있어 짧게 폴링한다.
        boolean shardsCreated = awaitTrue(
                () -> campaignStockShardRepository.existsByCampaignId(campaignId),
                Duration.ofSeconds(3));

        assertThat(shardsCreated).as("reserve() 호출 없이도 생성 직후 샤드가 만들어졌는지").isTrue();
        assertThat(campaignStockShardRepository.sumRemainingStock(campaignId)).isEqualTo(100);
    }

    private boolean awaitTrue(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
