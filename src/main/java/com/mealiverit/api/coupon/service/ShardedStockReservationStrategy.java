package com.mealiverit.api.coupon.service;

import com.mealiverit.api.campaign.entity.Campaign;
import com.mealiverit.api.campaign.repository.CampaignRepository;
import com.mealiverit.api.campaign.repository.CampaignStockShardRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
// 2026-08-26: 신규 캠페인은 이제 생성 시점(CampaignShardInitListener, CampaignCreatedEvent
// AFTER_COMMIT)에 바로 샤드를 만든다 - ensureShardsExist()가 여기서도 그대로 재사용된다.
// 아래 "지연 생성" 로직 자체는 없애지 않았다: (1) 이 기능이 생기기 전에 만들어진 과거 캠페인,
// (2) 생성 이벤트 리스너가 아직 처리되기 전에(이론상으로만 가능) reserve()/rollback()이
// 먼저 들어오는 경우의 안전망으로 계속 필요하다. ensureShardsExist()는 멱등이라 생성 시점에
// 이미 만들어진 샤드에 대해 reserve()가 다시 호출해도 아무 일도 안 한다(빠른 경로로 즉시 반환).
//
// (이하는 지연 생성 로직 자체의 배경 - 이 기능이 생기기 전 캠페인들에게 여전히 적용됨)
// 샤드는 원래 캠페인 생성 시점이 아니라 이 전략이 처음 그 캠페인을 다룰 때 지연 생성했다
// (ensureShardsExist) - campaign.remaining_stock을 기준으로 나누므로, 샤딩 도입 이전에
// 이미 존재하던 캠페인(부분 발급된 것 포함)도 별도 백필 없이 정확한 값으로 자동 채워졌다.
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

    private final int shardCount;
    private final CampaignStockShardRepository shardRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignStockShardBatchCreator batchCreator;
    private final Set<Long> initializedCampaignIds = ConcurrentHashMap.newKeySet();
    // 캠페인별 생성-구간 락. 전역 락이 아니라 캠페인별로 나눈 이유: reserve()/rollback()마다
    // 항상 거치는 ensureShardsExist()의 빠른 경로(initializedCampaignIds에 이미 있음)는 락을
    // 아예 안 타므로, 이 맵은 "아직 초기화 안 된 캠페인"에서만, 그것도 초기화가 끝날 때까지의
    // 짧은 구간에만 쓰인다.
    private final ConcurrentHashMap<Long, Object> shardInitLocks = new ConcurrentHashMap<>();

    // 2026-08-27 진단용(캠페인 1285, 재고 10000/발급 10023): StockLossRepairJob·
    // recoverFromInsertFailure() 양쪽 다 이번엔 전혀 개입하지 않았는데도 초과발급이 재현됐다 -
    // coupon_issue INSERT는 반드시 이 클래스의 decreaseIfAvailable() 성공을 거쳐야만 나올 수 있는
    // 유일한 경로(전수 조사함)라, "DB의 원자적 UPDATE가 실제로 총 캐패시티보다 몇 번 더
    // 성공했는지"를 JVM 자체 카운터로 교차검증해야 한다. successCounters는 캠페인별 누적 성공
    // 횟수, totalCapacityByCampaign은 샤드 생성 시점에 기록해둔 "이 캠페인이 원래 가져야 할
    // 총량"이다 - 누적 성공이 이 값을 넘어서는 순간을 잡아내면 최소한 "DB UPDATE 자체가 정말
    // 초과 성공했는지" vs "coupon_issue에 이 경로를 안 거친 다른 원인으로 행이 생겼는지"를
    // 구분할 수 있다.
    private final ConcurrentHashMap<Long, AtomicInteger> successCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> totalCapacityByCampaign = new ConcurrentHashMap<>();

    // 2026-08-22 실측(Prometheus, coupon-duplicate-request-test.js): 샤드 10개로는 InnoDB row
    // lock wait가 100초 동안 36,652건, 누적 대기시간 16,190,778ms(건당 평균 442ms) 발생 - 락을
    // 오래 붙잡은 트랜잭션들이 HikariCP 커넥션(600개)을 그대로 물고 있어 풀이 완전히 고갈되고
    // (active=600/600, pending 최대 400) 그 뒤로 요청이 커넥션조차 못 받아 줄을 섰다. 재컴파일
    // 없이 이 값을 실험할 수 있게 상수 대신 설정값으로 뺐다 - 기본값은 이번 실측을 근거로 10에서
    // 상향한 값이다.
    public ShardedStockReservationStrategy(CampaignStockShardRepository shardRepository,
                                            CampaignRepository campaignRepository,
                                            CampaignStockShardBatchCreator batchCreator,
                                            @Value("${app.stock-shard.count:50}") int shardCount) {
        this.shardRepository = shardRepository;
        this.campaignRepository = campaignRepository;
        this.batchCreator = batchCreator;
        this.shardCount = shardCount;
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
        int start = ThreadLocalRandom.current().nextInt(shardCount);
        for (int i = 0; i < shardCount; i++) {
            int shardIndex = (start + i) % shardCount;
            if (shardRepository.decreaseIfAvailable(campaignId, shardIndex) > 0) {
                recordSuccessAndDetectOvercapacity(campaignId, shardIndex);
                return true;
            }
        }
        return false;
    }

    // 2026-08-27 진단용 - 클래스 상단 successCounters 주석 참고. decreaseIfAvailable()의 원자적
    // UPDATE가 DB 차원에서 정말로 총 캐패시티를 넘겨 성공한 건지를, 별도의 DB 조회 없이 JVM
    // 카운터만으로 실시간 검출한다. 정상이라면 이 로그는 절대 안 찍혀야 한다 - 찍힌다면
    // decreaseIfAvailable() 자체의 원자성이 깨진 것이고, 안 찍히는데도 coupon_issue가 총량을
    // 넘는다면 원인이 이 클래스 바깥(이 경로를 안 거치는 다른 INSERT 원인)에 있다는 뜻이다.
    private void recordSuccessAndDetectOvercapacity(Long campaignId, int shardIndex) {
        int count = successCounters.computeIfAbsent(campaignId, id -> new AtomicInteger()).incrementAndGet();
        Integer capacity = totalCapacityByCampaign.get(campaignId);
        if (capacity != null && count > capacity) {
            log.error("[진단] decreaseIfAvailable 누적 성공 횟수가 총 캐패시티를 초과함 - "
                            + "campaignId={}, shardIndex={}, 누적성공={}, 총캐패시티={}, thread={}",
                    campaignId, shardIndex, count, capacity, Thread.currentThread().getName());
        }
    }

    @Override
    public void rollback(Long campaignId) {
        ensureShardsExist(campaignId);
        int start = ThreadLocalRandom.current().nextInt(shardCount);
        for (int i = 0; i < shardCount; i++) {
            int shardIndex = (start + i) % shardCount;
            if (shardRepository.increaseIfBelowCapacity(campaignId, shardIndex) > 0) {
                // 2026-08-27 진단용 - 정당한 원복(보상)은 "누적 순사용량"에서 빼줘야, 정상적인
                // 실패-재시도 churn을 successCounters의 오탐(허위 초과)으로 착각하지 않는다.
                AtomicInteger counter = successCounters.get(campaignId);
                if (counter != null) {
                    counter.decrementAndGet();
                }
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
    // CampaignShardInitListener(캠페인 생성 직후)와 reserve()/rollback()(지연 생성 폴백) 양쪽에서
    // 호출하므로 public - 멱등이라 몇 번을 다시 불러도 안전하다(이미 초기화된 캠페인은 즉시 반환).
    public void ensureShardsExist(Long campaignId) {
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
                    // 2026-08-26 진단 로깅 추가: 캠페인 생성 시점 이벤트 리스너(eager)와
                    // reserve()/rollback()의 지연 생성 폴백이 이론상 경쟁할 수 있는 지점이라,
                    // 실제로 이 분기를 몇 번, 어떤 값으로 타는지 남겨서 부하테스트 담당자가
                    // 리포트한 "재고가 나중에 totalStock으로 원복됨" 현상의 원인 후보(중복 생성)를
                    // 확인할 수 있게 한다.
                    log.info("재고 샤드 생성: campaignId={}, totalToDistribute={}",
                            campaignId, campaign.getRemainingStock());
                    createShards(campaignId, campaign.getRemainingStock());
                }
            } else {
                // 2026-08-27 진단 카운터 보정(캠페인 1286 재현 - 진단 카운터가 전혀 안 찍힘):
                // 이 JVM이 방금 재시작됐고, 이 캠페인의 샤드는 재시작 전에(또는 다른 인스턴스가)
                // 이미 만들어둔 경우 여기로 들어온다 - createShards()를 안 타므로
                // totalCapacityByCampaign이 끝까지 안 채워져 진단 카운터의 초과 검출 자체가
                // 무력화된다(capacity=null이라 비교식이 평가조차 안 됨). DB에서 캐패시티 합계를
                // 직접 읽어와 채워서 이 구멍을 막는다.
                totalCapacityByCampaign.putIfAbsent(campaignId, shardRepository.sumCapacity(campaignId));
            }
            initializedCampaignIds.add(campaignId);
        }
    }

    // 2026-08-22 1차 수정: shardRepository.insertIgnore()가 건마다 REQUIRES_NEW(=커넥션 새로
    // 획득)라, 샤드 수만큼 순차 커넥션 왕복이 synchronized 블록 안에서 일어났다 - 커넥션 1번으로
    // 전부 넣는 배치 INSERT로 바꿨다.
    //
    // 2026-08-22 2차 수정(round-10 실측, sys.innodb_lock_waits로 확인): 그 배치 INSERT를 여기서
    // JdbcTemplate으로 직접 실행했더니, 이 메서드를 호출한 reserve()가 실은 이미 바깥의
    // @Transactional(reserveStock()) 안에서 실행 중이라, 배치 INSERT가 그 트랜잭션에 편승해버렸다
    // - 새로 만든 샤드 50개 행의 락이 삽입 즉시가 아니라 reserveStock() 전체가 끝날 때까지 안
    // 풀렸다. 새 캠페인 첫 요청에 대량 트래픽이 몰리면 이 한 트랜잭션이 조금만 늦어져도 뒤따르는
    // 요청 전부가 InnoDB lock_wait_timeout까지 한꺼번에 밀렸다(트랜잭션 하나가 rows_modified=50인
    // 채로 1분 넘게 안 풀리며 수십 개 세션을 block하는 게 실제로 재현됨). CampaignStockShardBatchCreator로
    // 분리해 REQUIRES_NEW로 실행되게 했다 - 커넥션 왕복은 여전히 1회, 그 1회짜리 트랜잭션은
    // 바깥과 무관하게 즉시 커밋되어 락도 즉시 풀린다.
    private void createShards(Long campaignId, int totalToDistribute) {
        // 2026-08-27 진단용 - 클래스 상단 successCounters 주석 참고. putIfAbsent인 이유: 지연
        // 생성 폴백이 나중에 다시 호출되더라도(멱등 - 이미 초기화된 캠페인은 애초에 이 메서드까지
        // 안 옴) 최초 총량만 유지한다.
        totalCapacityByCampaign.putIfAbsent(campaignId, totalToDistribute);
        int base = totalToDistribute / shardCount;
        int remainder = totalToDistribute % shardCount;
        List<Object[]> batchArgs = new ArrayList<>(shardCount);
        for (int shardIndex = 0; shardIndex < shardCount; shardIndex++) {
            int value = base + (shardIndex < remainder ? 1 : 0);
            batchArgs.add(new Object[] {campaignId, shardIndex, value, value});
        }
        batchCreator.createAll(batchArgs);
    }
}
