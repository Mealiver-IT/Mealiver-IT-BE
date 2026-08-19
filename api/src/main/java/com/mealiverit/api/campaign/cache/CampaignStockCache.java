package com.mealiverit.api.campaign.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// DB 선반영 -> 스냅샷 생성 -> Redis 반영 구조(2026-08-19 멘토링 피드백)의 Redis 쪽 담당.
// Redis는 발급 여부를 절대 직접 결정하지 않는다 - 최종 정합성 판단은 항상 DB(비관적 락 +
// remaining_stock)가 하고, 여기는 그 결과를 사후에 복사해두는 캐시일 뿐이다. 그래서 Redis가
// 장애로 응답 못해도 예외를 삼키고 로그만 남긴다 - 캐시 갱신 실패가 발급 트랜잭션에 영향을
// 주면 안 된다(호출 시점은 어차피 AFTER_COMMIT이라 트랜잭션은 이미 끝난 뒤이기도 하다).
@Component
public class CampaignStockCache {

    private static final Logger log = LoggerFactory.getLogger(CampaignStockCache.class);
    private static final String KEY_PREFIX = "stock:";

    private final StringRedisTemplate redisTemplate;

    public CampaignStockCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void updateSnapshot(Long campaignId, int remainingStock) {
        try {
            redisTemplate.opsForValue().set(key(campaignId), String.valueOf(remainingStock));
        } catch (DataAccessException e) {
            log.warn("Redis 재고 스냅샷 갱신 실패 (campaignId={}) - 캐시만 실패, DB는 정상 반영됨", campaignId, e);
        }
    }

    // 사전 필터에서 사용할 조회. Redis 값이 없거나(캐시 미스) 장애 시에는 "확실히 품절"이라고
    // 단정할 수 없으므로 null을 반환한다 - 호출측은 null이면 DB로 그대로 보내야 한다(캐시는
    // 어디까지나 보조 신호이지, 이 값이 없다고 품절로 취급하면 안 된다).
    public Integer getSnapshot(Long campaignId) {
        try {
            String value = redisTemplate.opsForValue().get(key(campaignId));
            return value == null ? null : Integer.valueOf(value);
        } catch (DataAccessException e) {
            log.warn("Redis 재고 스냅샷 조회 실패 (campaignId={}) - DB로 폴백", campaignId, e);
            return null;
        }
    }

    private String key(Long campaignId) {
        return KEY_PREFIX + campaignId;
    }
}
