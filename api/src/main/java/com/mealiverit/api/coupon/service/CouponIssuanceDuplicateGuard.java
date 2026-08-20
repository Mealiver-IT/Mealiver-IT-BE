package com.mealiverit.api.coupon.service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 2026-08-19 부하테스트(coupon_mixed_5k_x4.js) 실측: 같은 유저가 짧은 시간에 여러 번(다른
// idempotencyKey로) 요청하면 idempotency 체크(락 없는 SELECT)를 전부 통과해버려서, N개 요청이 전부
// 캠페인 row 락을 잡으러 가고 그중 하나만 uk_campaign_user에서 성공하고 나머지는 실패 후
// compensateStockRollback()에서 락을 한 번 더 잡는다 - 유저 1명당 락 소모가 최악 (N + N-1)번까지
// 증폭됨. 이건 재고 판단과 무관한 순수 "중복요청 억제"라서 CampaignStockCache처럼 Redis를 의사결정에
// 쓰지 않고, 이미 진행 중인 (campaignId, userId) 조합만 짧게 걸러내는 사전 필터로만 쓴다 - Redis가
// 이 키를 잃어버려도(장애/재시작) 중복 요청이 그냥 오늘까지의 동작(uk 제약 + 보상 롤백)으로
// 안전하게 처리될 뿐, 초과발급 위험은 전혀 늘지 않는다.
@Component
public class CouponIssuanceDuplicateGuard {

    private static final Logger log = LoggerFactory.getLogger(CouponIssuanceDuplicateGuard.class);
    private static final String KEY_PREFIX = "dup:";

    // 근접 중복요청 폭주 구간만 흡수하면 되는 짧은 창 - 너무 길면 등급변경 등으로 인한 정당한
    // 재시도까지 불필요하게 막는다.
    private static final Duration GUARD_TTL = Duration.ofSeconds(10);

    private final StringRedisTemplate redisTemplate;

    public CouponIssuanceDuplicateGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // true = 이 요청이 먼저 통과함(정상 진행). false = 같은 (campaignId, userId) 요청이 이미 진행 중.
    public boolean tryAcquire(Long campaignId, Long userId) {
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key(campaignId, userId), "1", GUARD_TTL);
            // Redis 장애/타임아웃으로 acquired가 null이면 판단 불가 - 걸러내지 말고 통과시켜
            // DB(uk_campaign_user)가 최종 판단하게 한다.
            return !Boolean.FALSE.equals(acquired);
        } catch (DataAccessException e) {
            log.warn("중복요청 가드 확인 실패 (campaignId={}, userId={}) - 통과시키고 DB가 최종 판단",
                    campaignId, userId, e);
            return true;
        }
    }

    private String key(Long campaignId, Long userId) {
        return KEY_PREFIX + campaignId + ":" + userId;
    }
}
