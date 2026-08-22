package com.mealiverit.api.coupon.service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
//
// 2026-08-22 부하테스트(coupon-duplicate-request-test.js, 5,000명×4 동시요청) 실측: TTL(그때
// 5초)만으로 해제하니, 부하로 Tomcat 워커 스레드 배정이 늦어지면 같은 유저의 나머지 요청들이
// tryAcquire()를 호출하는 시점엔 이미 원본 요청의 키가 만료돼 있어 가드를 그냥 통과해버렸다 -
// 전체 "중복" 요청의 44.6%(5,816/13,027)가 가드가 아니라 DB(uk_campaign_user 위반 + 보상 롤백)
// 경로로 샜다 - 이 클래스가 막으려던 락 증폭이 TTL 만료 때문에 그대로 재현됨. 그래서 요청이
// 끝나는 시점(성공/실패 무관)에 release()로 즉시 해제하도록 바꿨다 - 정상 흐름에서는 처리
// 시간이 얼마가 걸리든 가드가 유효한 채로 남아있는다. TTL은 release() 호출 자체가 실패하는
// 경우(인스턴스 크래시 등)에만 발동하는 최후 안전장치로 격하됐다.
@Component
public class CouponIssuanceDuplicateGuard {

    private static final Logger log = LoggerFactory.getLogger(CouponIssuanceDuplicateGuard.class);
    private static final String KEY_PREFIX = "dup:";

    // release()가 정상적으로 불리는 한 이 값은 정상 흐름에 영향을 주지 않는다 - 인스턴스가
    // 크래시해서 release()를 못 부른 키만 이 시간 뒤에 자연 해소된다.
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
        } catch (Exception e) {
            // CampaignStockCache와 동일한 이유(2026-08-20 실측) - 커넥션 자체가 안 되면 Lettuce
            // 예외가 DataAccessException으로 번역 안 된 채 새어나갈 수 있어 Exception 전체를 잡는다.
            log.warn("중복요청 가드 확인 실패 (campaignId={}, userId={}) - 통과시키고 DB가 최종 판단",
                    campaignId, userId, e);
            return true;
        }
    }

    // tryAcquire()로 통과한 요청이 끝났을 때(성공이든 실패든) 반드시 호출해야 한다 - 그래야
    // 처리 시간과 무관하게 다음 요청이 곧바로 가드를 다시 통과할 수 있다.
    public void release(Long campaignId, Long userId) {
        try {
            redisTemplate.delete(key(campaignId, userId));
        } catch (Exception e) {
            // 해제 실패해도 GUARD_TTL이 결국 지워준다 - 초과발급 위험은 없고, 그 사이 짧게
            // 잘못 거절될 수 있는 성능 문제일 뿐이라 예외를 전파하지 않는다.
            log.warn("중복요청 가드 해제 실패 (campaignId={}, userId={}) - TTL 만료로 자연 해소됨",
                    campaignId, userId, e);
        }
    }

    private String key(Long campaignId, Long userId) {
        return KEY_PREFIX + campaignId + ":" + userId;
    }
}
