# Architecture — 도메인 모델 및 동시성 제어 전략

관련: [PRD.md](./PRD.md) | [system_design.md](./system_design.md) | [tech_doc.md](./tech_doc.md) | [task_list.md](./task_list.md)

## 1. 도메인 모델

```
User (mock, 회원가입/로그인 없음)
 ├─ id (PK, BIGINT)
 ├─ login_id (VARCHAR, UNIQUE) -- 가상 식별자
 ├─ name (VARCHAR)            -- PII, 마스킹 대상
 ├─ phone (VARCHAR)           -- PII, 마스킹 대상
 ├─ email (VARCHAR)           -- PII, 마스킹 대상
 └─ created_at

Campaign
 ├─ id (PK, BIGINT)
 ├─ name (VARCHAR)
 ├─ total_stock (INT)          -- 최초 재고, 불변
 ├─ remaining_stock (INT)      -- 동시성 제어 대상 카운터 (전략에 따라 사용 여부 다름)
 ├─ open_at (DATETIME)         -- 예약 오픈 (선택 확장)
 ├─ close_at (DATETIME)
 ├─ status (ENUM: READY, OPEN, CLOSED)
 └─ version (BIGINT)           -- @Version, 낙관적 락 옵션용

Coupon (캠페인이 발급하는 쿠폰 정책 마스터. 캠페인:쿠폰 1:1로 단순화 가능)
 ├─ id (PK)
 ├─ campaign_id (FK)
 ├─ discount_type (ENUM: FIXED, RATE)
 ├─ discount_value (DECIMAL)
 └─ valid_days (INT)           -- 발급일로부터 유효기간

CouponIssue (발급 이력 = 상태 관리 핵심 테이블, 300만 건 대상)
 ├─ id (PK, BIGINT, AUTO_INCREMENT)
 ├─ campaign_id (FK)
 ├─ user_id (FK)
 ├─ coupon_code (VARCHAR, UNIQUE)      -- 발급된 실물 쿠폰 식별자
 ├─ status (ENUM: ISSUED, USED, CANCELED, EXPIRED)
 ├─ idempotency_key (VARCHAR, UNIQUE)  -- 발급 요청 중복 방지 키
 ├─ issued_at (DATETIME NOT NULL)
 ├─ used_at (DATETIME NULL)
 ├─ canceled_at (DATETIME NULL)
 ├─ expired_at (DATETIME NULL)
 ├─ version (BIGINT NOT NULL DEFAULT 0)  -- @Version, 상태전이 낙관적 락
 └─ created_at / updated_at

CouponStateLog (상태전이 감사로그 — 검증 배치가 "이력 vs 카운터" 비교할 근거)
 ├─ id (PK)
 ├─ coupon_issue_id (FK)
 ├─ from_status
 ├─ to_status
 ├─ request_id (VARCHAR)   -- 상태변경 요청의 idempotency key
 └─ created_at
```

## 2. 필수 제약조건 (Defense in Depth)

어떤 동시성 전략을 앱 레벨에서 선택하든, DB 제약이 최후 방어선 역할을 한다.

```sql
-- 1인 1매 보장의 최종 방어선: 어떤 동시성 전략을 쓰든 이 제약이 없으면 초과발급 가능
ALTER TABLE coupon_issue
  ADD CONSTRAINT uk_campaign_user UNIQUE (campaign_id, user_id);

-- 발급 요청 중복(재시도, 더블클릭, 네트워크 재전송) 방지
ALTER TABLE coupon_issue
  ADD CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key);

-- 쿠폰 코드 자체의 유일성
ALTER TABLE coupon_issue
  ADD CONSTRAINT uk_coupon_code UNIQUE (coupon_code);

-- 상태전이 동시 중복요청에 대한 낙관적 락
ALTER TABLE coupon_issue ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

`uk_campaign_user`가 핵심이다. 애플리케이션 레벨 동시성 제어(Redis, Lua, 분산락 등)가 버그로 뚫리더라도, 이 유니크 제약이 있으면 **DB가 물리적으로 2번째 INSERT를 거부**한다. 재고 초과(수량 초과) 자체는 이 제약만으로는 못 막으므로 아래 3절의 전략이 필요하지만, "1인 1매"는 이 제약 하나로 100% 보장된다.

## 3. 상태 머신

```java
public enum CouponStatus {
    ISSUED, USED, CANCELED, EXPIRED;

    private static final Map<CouponStatus, Set<CouponStatus>> TRANSITIONS = Map.of(
        ISSUED,   Set.of(USED, CANCELED, EXPIRED),
        USED,     Set.of(CANCELED),   // 사용 후 취소(환불)만 허용, 그 외 역행 불가
        CANCELED, Set.of(),
        EXPIRED,  Set.of()
    );

    public boolean canTransitionTo(CouponStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
```

## 4. 동시성 제어 전략 비교 (팀 회의 안건)

재고 초과 방지(수량 제어)를 위한 5가지 후보. "1인 1매"는 2절의 unique constraint로 전략과 무관하게 보장되므로, 아래 비교는 **"재고 카운트 차감의 원자성을 어떻게 보장할 것인가"**에 초점을 둔다.

| 전략 | 정합성 보장 | 처리량 | 구현 복잡도 | 인프라 의존성 | 실패 모드 |
|---|---|---|---|---|---|
| **(a) DB unique constraint + 비관적 락** (`SELECT ... FOR UPDATE`) | 강함. 트랜잭션 내 row lock으로 완전 직렬화 | 낮음. 락 경합 시 대기 큐 발생, 재고 row 하나에 모든 요청이 몰림(hot row) | 낮음. `@Lock(PESSIMISTIC_WRITE)` 한 줄 | MySQL만 있으면 됨 | 락 대기 타임아웃, 데드락 가능성, DB 커넥션 풀 고갈 위험 |
| **(b) DB unique constraint + 낙관적 락/재시도** (`@Version`) | 강함이나 재시도 로직 필수 | 중간. 경합 심하면 재시도 폭증(thundering herd)으로 오히려 저하 | 중간. `spring-retry` `@Retryable` + 백오프 필요 | MySQL만 있으면 됨 | 재시도 스톰, 재시도 한도 초과 시 사용자 응답 실패 |
| **(c) Redis 원자적 감소** (`DECR` 또는 Lua script)를 DB 쓰기 앞단 게이트키퍼로 사용 | 강함(단일 스레드 원자성). Redis-DB 간 정합성은 별도 검증 필요 | 높음. 초당 수만 건 처리 가능, DB는 통과된 요청만 받음 | 중간. Lua script + 실패시 롤백(재고 복구) 로직 필요 | Redis 필수 | Redis 장애 시 전체 발급 중단, Redis-DB 불일치(보상 트랜잭션 필요) |
| **(d) Redis + 메시지 큐(Kafka)로 비동기 분리** | 강함(게이트는 c와 동일) + 처리량/내구성 향상. eventual consistency 발생 | 매우 높음. 트래픽 스파이크를 큐가 흡수 | 높음. Kafka 운영, Consumer 멱등성, 순서보장, DLQ 설계 필요 | Redis + Kafka | 컨슈머 지연/장애 시 시차 발생, 큐 적체, 메시지 유실/중복 |
| **(e) Redisson 분산락** (`RLock`) | 강함. 캠페인 단위 락으로 임계구역 직렬화 | 낮음~중간 | 중간. lease time/watchdog 튜닝, 락 해제 누락 방지 필요 | Redis 필수 | Redis 장애 시 락 획득 불가, 타임아웃 미스로 중복 통과 가능성 |

### 4.1 권장안

**MVP 단계: (a) DB unique constraint + 비관적 락**
- 별도 인프라(Redis) 없이 MySQL만으로 "초과발급 0건"을 가장 확실하게, 가장 적은 코드로 증명 가능.
- 평가 핵심이 "정확성"이지 "처리량"이 아니므로, 우선 정확성을 낮은 복잡도로 확보하고 부하테스트로 입증하는 것이 리스크가 가장 낮음.

**하드닝 단계: (c) Redis 원자적 감소(Lua script)를 게이트키퍼로 추가**
- (a)의 한계(DB 커넥션/락 병목)를 극복하며, 단일 Redis 인스턴스의 단일 스레드 특성으로 원자성 유지.
- DB에는 "재고 확보에 성공한 요청만" 도달하므로 DB 부하도 감소.
- Redis-DB 간 정합성 어긋남은 보상 로직(재고 복구 INCR) + 검증 배치([system_design.md](./system_design.md) 참고)로 이중 방어.

**(d) Kafka 비동기 분리와 (e) Redisson**은 선택 확장으로 남김. (d)는 eventual consistency가 "즉시 정합성 검증" 평가 포인트와 설명 부담이 크고, (e)는 (a)/(c) 대비 이점이 뚜렷하지 않아 우선순위를 낮춤.

**최종 결정은 팀 회의에서.**

## 5. Idempotency 설계

### 5.1 발급(Issue) — Idempotency Key 기반

클라이언트는 요청마다 `Idempotency-Key` 헤더(예: `{userId}-{campaignId}` 또는 UUID)를 전송. `coupon_issue.idempotency_key`에 UNIQUE 제약으로 동일 키 재요청 시 DB가 두 번째 INSERT를 거부.

```java
@Transactional
public IssueResult issue(Long userId, Long campaignId, String idempotencyKey) {
    // 1) 이미 처리된 요청인지 먼저 확인 (조회로 빠른 반환)
    Optional<CouponIssue> existing = couponIssueRepository.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
        return IssueResult.alreadyProcessed(existing.get()); // 재요청도 동일 응답
    }

    // 2) 재고 확보 (전략에 따라 Redis Lua / DB 비관적락)
    boolean reserved = stockReservationService.reserve(campaignId);
    if (!reserved) throw new SoldOutException();

    try {
        // 3) INSERT — uk_campaign_user, uk_idempotency_key 가 최종 방어선
        CouponIssue issue = CouponIssue.issuedNow(userId, campaignId, idempotencyKey);
        couponIssueRepository.save(issue);
        return IssueResult.success(issue);
    } catch (DataIntegrityViolationException e) {
        // unique 제약 위반 = 동시에 같은 요청/유저가 통과됨 → 재고 원복 후 기존 레코드 반환
        stockReservationService.rollback(campaignId);
        return IssueResult.alreadyProcessed(
            couponIssueRepository.findByCampaignIdAndUserId(campaignId, userId).orElseThrow());
    }
}
```

### 5.2 상태전이(사용/취소/만료) — 상태 머신 + 낙관적 락

```java
@Transactional
public void use(Long couponIssueId, String requestId) {
    // request_id 로 CouponStateLog 조회 → 이미 처리된 요청이면 즉시 리턴 (idempotent)
    if (couponStateLogRepository.existsByRequestId(requestId)) return;

    CouponIssue issue = couponIssueRepository.findById(couponIssueId).orElseThrow();
    if (!issue.getStatus().canTransitionTo(CouponStatus.USED)) {
        throw new InvalidStateTransitionException(issue.getStatus(), CouponStatus.USED);
    }
    issue.markUsed(); // 내부에서 status=USED, used_at=now, version++ (JPA @Version 자동 관리)
    couponStateLogRepository.save(new CouponStateLog(issue.getId(), issue.getStatus(), USED, requestId));
    // save 시점에 낙관적 락 충돌(OptimisticLockingFailureException) 발생하면 spring-retry로 재시도
}
```

`@Version` 컬럼으로 동시 상태변경 요청 중 하나만 성공하고 나머지는 `OptimisticLockingFailureException` → `spring-retry`의 `@Retryable(retryFor = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay=50, multiplier=2))`로 재시도 후 최신 상태 기준 재판정. `request_id`(=idempotency key) 유니크 제약을 `coupon_state_log`에도 걸어 상태변경 API도 발급과 동일한 멱등 패턴을 따르게 한다.
