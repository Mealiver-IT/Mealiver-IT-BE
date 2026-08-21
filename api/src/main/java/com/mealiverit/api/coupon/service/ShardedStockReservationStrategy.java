package com.mealiverit.api.coupon.service;

import com.mealiverit.entity.campaign.Campaign;
import com.mealiverit.entity.campaign.CampaignRepository;
import com.mealiverit.entity.campaign.CampaignStockShardRepository;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// V6 — 재고 샤딩(2026-08-20). PessimisticLockStockReservationStrategy(원자 UPDATE, 락 보유시간
// 축소)까지 적용해도 coupon_mixed_5k_x4.js 부하테스트에서 목표 달성률이 80%대에 머물렀다 - 20,000건이
// 결국 campaign row 하나로 몰리는 처리량 자체의 한계였다(HikariCP 풀도 이 직렬화 대기 때문에
// 소진됨, Prometheus 실측). 캠페인 재고를 N개의 독립된 row(CampaignStockShard)로 쪼개서,
// InnoDB가 서로 다른 샤드는 동시에 잠글 수 있게 한다.
//
// 샤드 배정은 요청마다 고정이 아니라 매번 랜덤 시작 + 순차 폴백이다 - 하필 빈 샤드부터 시도해도
// 다른 샤드에 재고가 남아있으면 자동으로 찾아간다. 재고가 넉넉한 구간에는 대부분 1번 시도로
// 끝나 경합이 N분의 1로 분산되고, 막판 소진 직전에만 여러 샤드를 순회하는 비용이 붙는다.
//
// 샤드는 캠페인 생성 시점이 아니라 이 전략이 처음 그 캠페인을 다룰 때 지연 생성한다
// (ensureShardsExist) - campaign.remaining_stock을 기준으로 나누므로, 이 마이그레이션 이전에
// 이미 존재하던 캠페인(부분 발급된 것 포함)도 별도 백필 없이 정확한 값으로 자동 채워진다.
// 인스턴스별 인메모리 집합으로 "이미 확인한 캠페인"을 기억해 이후 요청마다 존재여부 조회가
// 반복되지 않게 한다. 생성 구간 자체는 캠페인별 락으로 원자화한다 - 2026-08-21 부하테스트(캠페인
// 300, 10~11회차) 실측으로 발견된 문제 참고: 락 없이는 샤드 1개만 커밋된 순간 다른 스레드가
// existsByCampaignId()=true를 보고 나머지 9개 생성을 건너뛸 수 있었다(INSERT IGNORE는 "동시에
// 같은 값을 중복 삽입"만 안전하게 막아줄 뿐, "생성이 끝나기도 전에 남이 끝났다고 오판"하는
// 건 못 막는다). ensureShardsExist() 주석 참고.
//
// 이 인메모리 집합에는 근본적인 한계가 하나 더 있다: 서버 프로세스가 살아있는 동안은
// 절대 스스로 안 지워진다. 부하테스트 리셋 SQL이 DELETE FROM campaign_stock_shard로 DB의
// 샤드를 지워도 서버는 그 사실을 모르고 "이미 준비 끝났음"을 계속 믿는다 - 재시작 없이는
// 리셋을 몇 번 해도 전 샤드 SOLD_OUT만 재현되는 문제가 실측됨(2026-08-21, 캠페인 300 13회차).
// reserve()가 전 샤드 실패로 진짜 품절을 선언하기 직전에 자가치유하도록 대응했다 - 상세는
// reserve() 주석 참고.
@Component
public class ShardedStockReservationStrategy implements StockReservationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ShardedStockReservationStrategy.class);
    private static final int SHARD_COUNT = 10;

    private final CampaignStockShardRepository shardRepository;
    private final CampaignRepository campaignRepository;
    private final Set<Long> initializedCampaignIds = ConcurrentHashMap.newKeySet();
    // 캠페인별 생성-구간 락. 전역 락이 아니라 캠페인별로 나눈 이유: reserve()/rollback()마다
    // 항상 거치는 ensureShardsExist()의 빠른 경로(initializedCampaignIds에 이미 있음)는 락을
    // 아예 안 타므로, 이 맵은 "아직 초기화 안 된 캠페인"에서만, 그것도 초기화가 끝날 때까지의
    // 짧은 구간에만 쓰인다.
    private final ConcurrentHashMap<Long, Object> shardInitLocks = new ConcurrentHashMap<>();

    public ShardedStockReservationStrategy(CampaignStockShardRepository shardRepository,
                                            CampaignRepository campaignRepository) {
        this.shardRepository = shardRepository;
        this.campaignRepository = campaignRepository;
    }

    @Override
    public boolean reserve(Long campaignId) {
        ensureShardsExist(campaignId);
        if (tryDecreaseAcrossShards(campaignId)) {
            return true;
        }
        // 2026-08-21 실측: 테스트 리셋 SQL이 DELETE FROM campaign_stock_shard로 샤드를 통째로
        // 지워도, 서버는 initializedCampaignIds에 이 캠페인이 이미 있다고 믿고 DB를 다시 안
        // 본다 - 재시작 전까지는 리셋을 몇 번 해도 계속 전 샤드 SOLD_OUT만 난다. 그래서 진짜
        // 품절을 선언하기 직전에 딱 한 번, 샤드가 실제로(전혀) 없는 상태인지 확인해서 그렇다면
        // 스스로 무효화하고 재생성한다 - 정상 경로(샤드 있음)에는 이 확인이 아예 안 실행되므로
        // 오버헤드가 없다.
        if (!shardRepository.existsByCampaignId(campaignId)) {
            initializedCampaignIds.remove(campaignId);
            ensureShardsExist(campaignId);
            return tryDecreaseAcrossShards(campaignId);
        }
        return false;
    }

    private boolean tryDecreaseAcrossShards(Long campaignId) {
        int start = ThreadLocalRandom.current().nextInt(SHARD_COUNT);
        for (int i = 0; i < SHARD_COUNT; i++) {
            int shardIndex = (start + i) % SHARD_COUNT;
            if (shardRepository.decreaseIfAvailable(campaignId, shardIndex) > 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void rollback(Long campaignId) {
        ensureShardsExist(campaignId);
        int start = ThreadLocalRandom.current().nextInt(SHARD_COUNT);
        for (int i = 0; i < SHARD_COUNT; i++) {
            int shardIndex = (start + i) % SHARD_COUNT;
            if (shardRepository.increaseIfBelowCapacity(campaignId, shardIndex) > 0) {
                return;
            }
        }
        // 모든 샤드가 각자의 capacity에 이미 도달한 상태 - 정상 흐름에서는 방금 이 reserve()가
        // 차감한 만큼은 항상 여유가 있어야 하므로 발생하면 안 된다. 재고 유실보다는 로그로
        // 남기고 넘어가는 편이 안전하다(예외를 던지면 이미 실패한 발급 흐름의 예외 처리를 더 꼬이게 함).
        log.warn("재고 원복 실패 - 모든 샤드가 capacity에 도달함 (campaignId={})", campaignId);
    }

    // 2026-08-21 실측 버그 수정: 락 없이 "existsByCampaignId()=false면 생성" 만으로는, 샤드
    // 1개만 먼저 커밋된 순간 다른 스레드가 "이미 있음"으로 오판해 나머지 9개 생성을 건너뛸 수
    // 있었다(20,000 동시요청처럼 극단적 동시성에서 실제 재현됨) - 그 캠페인은 이후 영구히
    // 샤드 1~2개분 재고로 쪼그라들어 멀쩡한 재고인데도 SOLD_OUT이 쏟아진다.
    // 캠페인별 락으로 "확인 + 생성"을 원자화한다 - double-checked locking: 락 밖에서 먼저
    // 빠르게 확인해 이미 끝난 경우 락 자체를 안 타게 하고(정상 트래픽 대부분이 여기서 끝남),
    // 락 안에서 한 번 더 확인해 대기하던 다른 스레드들이 중복 생성하지 않게 한다.
    private void ensureShardsExist(Long campaignId) {
        if (initializedCampaignIds.contains(campaignId)) {
            return;
        }
        Object lock = shardInitLocks.computeIfAbsent(campaignId, id -> new Object());
        synchronized (lock) {
            if (initializedCampaignIds.contains(campaignId)) {
                return;
            }
            if (!shardRepository.existsByCampaignId(campaignId)) {
                Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
                if (campaign != null) {
                    createShards(campaignId, campaign.getRemainingStock());
                }
            }
            initializedCampaignIds.add(campaignId);
        }
    }

    private void createShards(Long campaignId, int totalToDistribute) {
        int base = totalToDistribute / SHARD_COUNT;
        int remainder = totalToDistribute % SHARD_COUNT;
        for (int shardIndex = 0; shardIndex < SHARD_COUNT; shardIndex++) {
            int value = base + (shardIndex < remainder ? 1 : 0);
            shardRepository.insertIgnore(campaignId, shardIndex, value);
        }
    }
}
