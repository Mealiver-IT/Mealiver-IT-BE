package com.mealiverit.api.campaign.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

// 선착순 대기열 상태 조회(GET /api/campaigns/{campaignId}/queue) 저장소
// CampaignStockCache와 동일한 원칙: Redis 장애를 절대 요청 흐름에 전파하지 않는다 - 예외를 전부 삼키고 로그만 남긴다.
// 이 큐는 실제 발급 API(CouponIssuanceService)를 게이팅하지 않는 '안내용 조회'라,
// 장애 시 순번 정보 없이라도 발급 자체는 항상 계속 가능해야 한다.
@Component
public class CampaignQueueCache {

    private static final Logger logger = LoggerFactory.getLogger(CampaignQueueCache.class);
    private static final String KEY_PREFIX = "queue:";

    // CampaignStockCache.SNAPSHOT_TTL(60초)과 달리 대기열은 사람이 실제로 기다리는 대상이라 넉넉히 잡음
    // 캠페인이 오래 열려있어도 정리 스케줄러가 없어 이 시간 후 자연 소멸
    private static final Duration QUEUE_TTL = Duration.ofHours(6);

    private final StringRedisTemplate redisTemplate;

    public CampaignQueueCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ZADD NX로 이미 대기열에 있으면 아무것도 안 하고 최초 진입 시각을 그대로 유지 - 재조회해도 순번이 안 밀리는 핵심 로직
    // 등록 여부와 무관하게 TTL은 매 호출마다 갱신해서, 활발히 조회 중인 대기열은 만료되지 않게 한다.
    public void joinIfAbsent(Long campaignId, Long userId) {
        try {
            String key = key(campaignId);
            redisTemplate.opsForZSet().addIfAbsent(key, userId.toString(), Instant.now().toEpochMilli());
            redisTemplate.expire(key, QUEUE_TTL);
        } catch (Exception ex) {
            logger.warn("대기열 등록 실패 (campaignId={}, userId={}) - 순번 정보 없이 진행", campaignId, userId, ex);
        }
    }

    // 0-base rank -> 1-base 순번. 대기열에 없으면(장애로 등록 실패 등) null
    public Long rank(Long campaignId, Long userId) {
        try {
            Long rank = redisTemplate.opsForZSet().rank(key(campaignId), userId.toString());
            return rank == null ? null : rank+1;
        } catch (Exception ex) {
            logger.warn("대기열 순번 조회 실패 (campaignId={}, userId={})", campaignId, userId, ex);
            return null;
        }
    }

    public long size(Long campaignId) {
        try {
            Long size = redisTemplate.opsForZSet().zCard(key(campaignId));
            return size == null ? 0 : size;
        } catch (Exception ex) {
            logger.warn("대기열 인원 조회 실패 (campaignId={})", campaignId, ex);
            return 0;
        }
    }

    private String key(Long campaignId) {
        return KEY_PREFIX + campaignId;
    }
}
