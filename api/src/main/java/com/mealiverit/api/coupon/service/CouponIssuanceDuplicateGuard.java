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
// 2026-08-22 1차 수정(TTL만): 부하테스트(coupon-duplicate-request-test.js, 5,000명×4 동시요청)
// 실측 - TTL(그때 10초)만으로 해제하니, 부하로 Tomcat 워커 스레드 배정이 늦어지면 같은 유저의
// 나머지 요청들이 tryAcquire()를 호출하는 시점엔 이미 원본 요청의 키가 만료돼 있어 가드를 그냥
// 통과해버렸다 - 전체 "중복" 요청의 44.6%(5,816/13,027)가 가드가 아니라 DB(uk 제약 위반 + 보상
// 롤백) 경로로 샜다.
//
// 2026-08-22 2차 수정(release()만): 처리 시간과 무관하게 요청이 끝나는 즉시 release()로 해제하도록
// 바꿨더니, 오히려 already_processed(DB까지 새는 경로)가 5,816 -> 9,139로 더 늘고 지연시간도
// 악화됐다(재측정 round-04). 원인: TTL 방식은 "빨리 끝나도 최소 TTL만큼은 무조건 보호"였는데,
// release()만 쓰면 그 최소 보장이 사라져서 - 빨리 끝나는 요청일수록 보호 구간이 오히려 짧아졌다.
// 반대로 "처리 시간이 긴 요청을 실제로 끝날 때까지 보호"하는 효과는 있었지만, 이번 부하 조건에서는
// 전자(빠른 요청의 보호 구간 단축)가 더 크게 작용했다.
//
// 2026-08-22 3차 수정(최소 보유시간 + release() 결합): 두 효과를 다 취하기 위해 Redis 값에
// "1" 대신 획득 시각을 저장한다. release() 시점에 (지금 - 획득시각)이 MIN_HOLD보다 짧으면
// 즉시 지우지 않고 남은 MIN_HOLD만큼만 TTL을 줄여서 "최소한 MIN_HOLD까지는 보호"를 보장하고,
// MIN_HOLD를 넘겼으면(느린 요청) 그 자리에서 바로 지운다 - 처리 시간과 무관하게 실제 끝난
// 시점까지 보호한다. GUARD_TTL은 이제 release() 호출 자체가 실패하는 경우(인스턴스 크래시 등)의
// 최후 안전장치로, 관측된 처리시간 최댓값(round-04 p95 101s, max 110s)보다 넉넉히 잡는다.
@Component
public class CouponIssuanceDuplicateGuard {

    private static final Logger log = LoggerFactory.getLogger(CouponIssuanceDuplicateGuard.class);
    private static final String KEY_PREFIX = "dup:";

    // release() 자체가 실패했을 때만(인스턴스 크래시 등) 발동하는 최후 안전장치 - 관측된
    // 처리시간 최댓값(round-04 p95 101s, max 110s)보다 넉넉히 잡는다.
    private static final Duration GUARD_TTL = Duration.ofSeconds(120);

    // 처리가 이 시간보다 빨리 끝나도, 최소 이만큼은 가드를 유지한다(round-02에서 검증된
    // "TTL 10초"와 동일한 값 - 근접 중복요청 폭주 구간을 흡수하기에 충분했던 값을 그대로 최소
    // 보장선으로 재사용). 등급변경 등으로 인한 정당한 재시도가 이 시간만큼은 막힐 수 있다는
    // 뜻이기도 하다 - 너무 크게 잡지 않는다.
    private static final Duration MIN_HOLD = Duration.ofSeconds(10);

    private final StringRedisTemplate redisTemplate;

    public CouponIssuanceDuplicateGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // true = 이 요청이 먼저 통과함(정상 진행). false = 같은 (campaignId, userId) 요청이 이미 진행 중.
    // 값으로 "1" 대신 획득 시각(epoch millis)을 저장 - release()가 MIN_HOLD 경과 여부를
    // 판단하는 데 필요하다.
    public boolean tryAcquire(Long campaignId, Long userId) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key(campaignId, userId), String.valueOf(System.currentTimeMillis()), GUARD_TTL);
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

    // tryAcquire()로 통과한 요청이 끝났을 때(성공이든 실패든) 반드시 호출해야 한다.
    // MIN_HOLD를 이미 넘겼으면(느린 요청) 그 자리에서 바로 지워서 다음 요청이 곧바로 통과하게
    // 하고, 아직 못 넘겼으면(빠른 요청) 지우지 않고 TTL을 남은 MIN_HOLD 시간만큼만 줄여서
    // "최소 MIN_HOLD 보호"를 유지한다.
    public void release(Long campaignId, Long userId) {
        String key = key(campaignId, userId);
        try {
            String acquiredAtValue = redisTemplate.opsForValue().get(key);
            if (acquiredAtValue == null) {
                // 이미 지워졌거나(GUARD_TTL 만료) tryAcquire() 자체가 Redis 장애로 키를
                // 못 남긴 경우 - 할 일 없음.
                return;
            }
            long elapsedMillis = System.currentTimeMillis() - Long.parseLong(acquiredAtValue);
            if (elapsedMillis >= MIN_HOLD.toMillis()) {
                redisTemplate.delete(key);
            } else {
                redisTemplate.expire(key, Duration.ofMillis(MIN_HOLD.toMillis() - elapsedMillis));
            }
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
