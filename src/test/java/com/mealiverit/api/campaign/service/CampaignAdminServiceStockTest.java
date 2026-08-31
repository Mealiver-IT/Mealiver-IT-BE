package com.mealiverit.api.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealiverit.api.campaign.cache.CampaignStockCache;
import com.mealiverit.api.campaign.dto.CampaignStockResponse;
import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 2026-08-19: 실시간 재고 대시보드(GET /api/campaigns/{id}/stock)가 DB를 직접 조회해서 라이브 이벤트 중
// 발급 트랜잭션과 DB 커넥션을 다투던 문제 수정. Redis 스냅샷을 우선 쓰고, 캐시 미스일 때만 DB로
// 폴백하는지 검증한다.
@SpringBootTest
@Testcontainers
class CampaignAdminServiceStockTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private CampaignAdminService campaignAdminService;
    @Autowired
    private CampaignRepository campaignRepository;
    @Autowired
    private CampaignStockCache campaignStockCache;

    @Test
    void Redis_스냅샷이_있으면_DB값과_달라도_스냅샷을_우선_반환한다() {
        Long campaignId = createOpenCampaign(10_000);
        // DB는 10,000이지만 Redis 스냅샷은 3,000으로 다르게 설정 - 실시간 값이라는 걸 확인하기 위함.
        campaignStockCache.updateSnapshot(campaignId, 3_000);

        CampaignStockResponse response = campaignAdminService.getStock(campaignId);

        assertThat(response.remainingStock()).isEqualTo(3_000);
        assertThat(response.totalStock()).isEqualTo(10_000);
        assertThat(response.soldOut()).isFalse();
    }

    @Test
    void Redis_캐시_미스면_DB값으로_폴백한다() {
        Long campaignId = createOpenCampaign(500);
        // campaignStockCache에 아무것도 안 써서 캐시 미스(null) 상태를 그대로 둔다.

        CampaignStockResponse response = campaignAdminService.getStock(campaignId);

        assertThat(response.remainingStock()).isEqualTo(500);
    }

    private Long createOpenCampaign(int stock) {
        Campaign campaign = new Campaign("대시보드 테스트 캠페인", stock, null);
        campaign.open(LocalDateTime.now(), null);
        return campaignRepository.save(campaign).getId();
    }
}
