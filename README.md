# 🎫 밀리버릿 (Mealiver-IT)
> 배달앱 오픈런 선착순 쿠폰 발급 시스템

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-brightgreen)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-blue)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-7.x-red)](https://redis.io/)
[![Docker](https://img.shields.io/badge/Docker-latest-blue)](https://www.docker.com/)

---

## 📌 목차

1. [프로젝트 소개](#1-프로젝트-소개)
2. [팀원 소개](#2-팀원-소개)
3. [기술 스택](#3-기술-스택)
4. [시스템 아키텍처](#4-시스템-아키텍처)
5. [ERD](#5-erd)
6. [핵심 기능](#6-핵심-기능)
   - [동시성 제어 — 선착순 발급](#6-1-동시성-제어--선착순-발급)
   - [쿠폰 상태 머신](#6-2-쿠폰-상태-머신)
   - [Idempotency 설계](#6-3-idempotency-설계)
   - [멤버십 등급 시스템](#6-4-멤버십-등급-시스템)
   - [정합성 자기검증](#6-5-정합성-자기검증)
   - [더미데이터 파이프라인](#6-6-더미데이터-파이프라인)
7. [인프라 & 배포](#7-인프라--배포)
8. [트러블슈팅](#8-트러블슈팅)

---

## 1. 프로젝트 소개

### 배경

U+ 백엔드 과제 "대규모 트래픽 선착순 쿠폰 발급 시스템"을, **배민 스타일 배달앱에서 매일 오전 11시 정각에 여는 오픈런 할인쿠폰** 시나리오로 구체화한 프로젝트입니다. 회원가입/로그인은 구현하지 않고 가상 회원 데이터로 대체합니다.

핵심 요구사항은 명확합니다 — 재고 10,000장에 20,000명이 동시에 요청해도 **초과 발급 0건, 1인 최대 1매**. 그리고 발급/사용/취소/만료 이력 300만 건 전체에 대해, 같은 데이터로 재실행하면 같은 결과가 나오는 **결정론적 정합성 자기검증**.

> "20,000명 동시 요청"의 측정 조건은 출제 측(멘토) 확인을 거쳐 **테스트 유저 20,000명(중복 없음) / ramp-up 60초**로 전 팀 공통 확정되었습니다. 심사위원이 부하테스트를 직접 재현·실행할 수 있다는 안내도 함께 받아, 원커맨드 초기화·실행 스크립트를 준비합니다. 원문·전체 Q&A는 [`docs/planning/12_멘토답변_확정사항.txt`](docs/planning/12_멘토답변_확정사항.txt) 참고.

### 목표

| 목표 | 해결 기술 |
|---|---|
| 재고 10,000장·동시요청 20,000건에도 초과발급 0건, 1인 1매 | DB unique 제약 + 비관적 락(MVP) → Redis 이중 카운터(하드닝, 설계 확정) |
| 300만 건 발급이력 전체에 대한 결정론적 정합성 자기검증 | 재실행 시 동일 결과가 나오는 검증 SQL 5종(구현·실행 완료) + Spring Batch 자동화(Phase 2 선택 확장) |
| 회원 등급별 차등 혜택 (이등병~병장 4단계) | 완료 주문 수 기준 매월 1일 자동 재산정 배치 |
| 100만 유저·300만 발급이력 규모 실증 | 청크 배치 시더 + `rewriteBatchedStatements` 기반 대량 적재 파이프라인 |

### 프로젝트 기간

```
2026.08.06 ~ (2주 / LG 부트캠프 멘토링 2회, 발표 및 시상 있음)
```

---

## 2. 팀원 소개

**6인 · 4역할**로 구성되어 있습니다.

| 역할 | 이름 | 담당 도메인 |
|---|---|---|
| 동시성/발급 API — 발급 로직 | 김어진 | `CouponIssuanceService`, 재고 예약 전략(비관적 락 → Redis 이중 카운터) |
| 동시성/발급 API — 상태전이 로직 | 이진희 | 상태전이 API, 상태 머신, idempotency |
| 데이터/검증배치 — 더미데이터 생성 | 윤태형 | 더미데이터 시더(유저/오더/등급/캠페인/발급이력) |
| 데이터/검증배치 — 검증 SQL·배치 + PII 마스킹 | 정민주 | 정합성 검증 배치(`ConsistencyVerificationJob`), PII 마스킹 컨버터/시리얼라이저 |
| 인프라/부하테스트 | 이호성 | Docker Compose, k6 부하테스트(유저 20,000명 중복없음 / ramp-up 60초) |
| 프론트 | 소서아 | 이벤트/결제 페이지, 실시간 재고 카운트다운 화면 |

---

## 3. 기술 스택

### Backend

| 분류 | 기술 |
|---|---|
| 언어 / 프레임워크 | ![Java](https://img.shields.io/badge/Java-21-007396?style=for-the-badge&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white) ![Maven](https://img.shields.io/badge/Maven-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white) |
| ORM | ![Spring Data JPA](https://img.shields.io/badge/Spring%20Data%20JPA-59666C?style=for-the-badge&logo=hibernate&logoColor=white) |
| 배치 | Spring `@Scheduled` + ShedLock(분산락) — 등급 재산정(`MembershipTierBatchJob`), 쿠폰 만료(`CouponExpirationBatchJob`) 구현 완료. 정합성 검증 자동화(`ConsistencyVerificationJob`, Spring Batch)는 Phase 2 선택 확장 |
| 재시도 / 이벤트 | ![Spring Retry](https://img.shields.io/badge/Spring%20Retry-6DB33F?style=for-the-badge&logo=spring&logoColor=white) 상태전이 동시성 재시도, `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` 기반 알림 분리 |
| 분산 캐시 / 락 | ![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=for-the-badge&logo=redis&logoColor=white) — 이중 카운터 기반 재고 게이트 (설계 완료, 연동 예정) |

### Database

| 분류 | 기술 |
|---|---|
| RDBMS | ![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?style=for-the-badge&logo=mysql&logoColor=white) |
| 마이그레이션 | Flyway |

### Infra

| 분류 | 기술 |
|---|---|
| 컨테이너 | ![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white) (Docker Compose 구성 — 추후 작성) |
| 원격 DB | Tailscale로 연결되는 팀 공유 MySQL (학원 공용 서버) |
| CI | GitHub Actions — `main` 브랜치 push 시 Docker 이미지 빌드 후 GHCR에 푸시 |

---

## 4. 시스템 아키텍처

```
[클라이언트(웹, 이벤트·결제 페이지만 실제 동작 / 나머지는 정적 mockup)]
        │ REST
        ▼
[Spring Boot Application]
   ├─ Controller / Service 계층
   │    ├─ CouponClaimController : 선착순 발급 (POST /api/campaigns/{id}/coupons, Idempotency-Key 헤더 필수)
   │    │    └─ CouponIssuanceService → StockReservationStrategy (V1: 비관적 락 구현체)
   │    ├─ CouponController      : 내 발급 쿠폰 조회, 관리자 강제회수(revoke)
   │    │    └─ CouponIssueService (상태전이: markUsed/markCanceled/markReturnedToIssued)
   │    ├─ OrderController       : 주문 생성(결제완료 시 쿠폰 사용 처리) / 취소(쿠폰 본인 재사용 복귀)
   │    └─ CampaignController    : 캠페인 CRUD (관리자용)
   ├─ Notification : CouponIssuedEvent → @TransactionalEventListener(AFTER_COMMIT) + @Async
   │                  → MockNotificationSender (발급 트랜잭션과 완전 분리, FR-NOT-001)
   ├─ Batch    : MembershipTierBatchJob (매월 1일 등급 재산정), CouponExpirationBatchJob (쿠폰 만료)
   │             — 둘 다 ShedLock으로 다중 인스턴스 중복실행 방지
   ├─ Seed     : UserSeedRunner → OrderSeedRunner → MembershipTierSeedRunner
   │             → CampaignSeedRunner → CouponIssueSeedRunner (더미데이터 파이프라인)
   │
   ├─▶ [MySQL]  회원 / 오더 / 캠페인 / 쿠폰 / 발급이력 / 등급이력
   └─▶ [Redis]  재고 이중 카운터, 캠페인 게이트          ── 설계 완료, 연동 예정
```

### 로컬 / 팀 공유 개발 환경

```
Docker 환경
   └─ MySQL (local 프로필: 로컬 컨테이너 / remote 프로필: Tailscale로 연결되는 팀 공유 서버)

Redis 연동 — 추후 작성
```

자세한 실행 방법은 [`api/src/main/java/com/mealiverit/api/seed/README.md`](api/src/main/java/com/mealiverit/api/seed/README.md) 참고.

---

## 5. ERD

전체 컬럼을 포함한 정식 ERD는 [`docs/planning/06_ERD.dbml.txt`](docs/planning/06_ERD.dbml.txt) 참고(dbdiagram.io에 그대로 붙여넣기 가능). 아래는 도메인 간 관계를 단순화한 다이어그램입니다.

```
회원(users)
  ├─▶ 오더(orders) ──────────────▶ 등급재산정이력(membership_tier_log)
  │        [완료 주문 수 → 매월 1일 배치가 membership_tier 재산정]
  │
  └─▶ 발급이력(coupon_issue) ◀── 캠페인(campaign) ──▶ 쿠폰정책(coupon)  [1:1]
              │
              └─▶ 상태변경이력(coupon_state_log)
                   [ISSUED → USED / CANCELED / EXPIRED, 역행 불가]
```

- `campaign.min_membership_tier` (nullable) — 회원 전용 쿠폰 eligibility, NULL이면 전 회원 대상
- `coupon_issue.issued_membership_tier` — 발급 시점 등급 스냅샷 (이후 등급이 바뀌어도 이미 발급된 쿠폰의 할인율은 불변)
- `coupon_issue.idempotency_key` (unique) — 중복 발급 요청 방어
- `(campaign_id, user_id)` unique — 1인 1매 방어 (동시성 전략과 무관한 최종 방어선)

---

## 6. 핵심 기능

---

### 6-1. 동시성 제어 — 선착순 발급

> **V1.0 MVP 구현 완료** — 아래는 확정된 설계이자 실제 적용된 구현입니다 (`docs/planning/04_아키텍처.txt` 4절).

재고 초과 방지를 위한 6가지 전략을 비교한 뒤, **3단계 버전사다리**로 가기로 확정했습니다.

| 전략 | 정합성 | 처리량 | 인프라 의존성 | 채택 여부 |
|---|---|---|---|---|
| (a) DB unique + 비관적 락 (`SELECT ... FOR UPDATE`) | 강함 (row lock 완전 직렬화) | 낮음 (hot row 경합) | MySQL만 | **V1.0 MVP로 구현 완료** (`PessimisticLockStockReservationStrategy`) |
| (b) DB unique + 낙관적 락/재시도 (`@Version`) | 강함이나 재시도 로직 필수 | 중간 (경합 심하면 재시도 폭증) | MySQL만 | 채택 안 함 — (a)가 동일 목표를 더 단순하게 달성 |
| (c) Redis 원자적 감소(Lua script) 게이트 | 강함(단일 스레드 원자성) | 높음 | Redis 필수 | **V2.0 하드닝 1단계** — 설계 완료, 연동 예정 (검토 후 (f)로 대체 예정) |
| (d) Redis + Kafka 비동기 분리 | 강함 + eventual consistency | 매우 높음 | Redis + Kafka | 선택 확장으로 보류 — eventual consistency가 "즉시 정합성 검증" 평가 포인트와 설명 부담이 큼 |
| (e) Redisson 분산락 (`RLock`) | 강함 (캠페인 단위 락) | 낮음~중간 | Redis 필수 | 기각 — fencing token 부재(Kleppmann, 2016)로 정합성 목적에 부적합 |
| (f) Redis 이중 카운터 (`countReq`/`count` 분리) | 강함 (총 발급량이 재고를 절대 못 넘음이 증명됨) | 높음, Lua 대비 오버헤드 낮음 | Redis 필수 | **V2.1 최종 채택** — 설계 완료, 연동 예정 |

**확정 로드맵**: `V1.0 MVP = (a) 비관적 락` → `V2.0 = (c) Redis Lua (검토 후 대체)` → `V2.1 최종 채택 = (f) Redis 이중 카운터`. `StockReservationStrategy` 인터페이스로 전략을 분리해 두어, V2 전환 시 구현체만 교체하면 되도록 설계했습니다. 자세한 비교·근거는 [`04_아키텍처.md`](docs/planning/04_아키텍처.txt) 4절 참고.

Redis가 상태를 잃는 경우(강제 종료 후 재시작)에 대비해 방어선 2겹을 추가로 둘 예정입니다: 발급 트랜잭션 안에서 실행되는 **DB 조건부 UPDATE 백스톱**(`UPDATE campaign SET remaining_stock = remaining_stock - 1 WHERE id = ? AND remaining_stock > 0`)과, 앱 기동/Redis 복구 시 DB 실제 발급 수를 기준으로 Redis 카운터를 재동기화하는 **멱등한 워밍업 함수**입니다.

**k6 부하테스트 리허설 결과** (`api/src/test/K6/phase1/`):

| 시나리오 | 조건 | 결과 |
|---|---|---|
| Phase 1 리허설 (`phase1-rehearsal.js`) | 재고 100장 vs 요청 50건 | 2026-08-12 실행 완료 — 초과발급 없이 **50/50 전원 발급 성공** |
| Phase 3 본시험 (`coupon_race.js`) | 유저 20,000명, ramp-up 60초 | 코드 완료. 2026-08-12 1차 시도했으나 로컬 PC TCP 소켓 한계로 결과 신뢰 불가 — 환경 교체(WSL2 등) 후 재시도 예정 |

---

### 6-2. 쿠폰 상태 머신

`ISSUED → USED / CANCELED / EXPIRED`, 역행 불가 상태전이는 거부됩니다. `USED → ISSUED`(주문취소 시 본인 재사용 복귀)만 예외적으로 허용됩니다(2026-08-13 팀 결정). 허용 전이 목록은 엔티티 레벨에 구현·테스트 완료되어 있습니다.

```java
public enum CouponStatus {
    ISSUED, USED, CANCELED, EXPIRED;

    private static final Map<CouponStatus, Set<CouponStatus>> TRANSITIONS = Map.of(
            ISSUED, Set.of(USED, CANCELED, EXPIRED),
            USED, Set.of(CANCELED, ISSUED),   // CANCELED = 관리자 강제회수, ISSUED = 주문취소 시 재사용 복귀
            CANCELED, Set.of(),
            EXPIRED, Set.of()
    );

    public boolean canTransitionTo(CouponStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
```

**상태별 의미**

| 상태 | 의미 |
|---|---|
| `ISSUED` | 발급 직후의 기본 상태. 사용 가능한 쿠폰(발급 API 성공 시의 최초 상태이자, 아래 `USED → ISSUED`로 복귀했을 때의 상태이기도 함) |
| `USED` | 주문에 쿠폰을 적용해 결제가 완료된 상태 (`OrderService`가 결제완료 처리 중 `markUsed` 호출) |
| `CANCELED` | **종단 상태.** 관리자가 강제로 회수한 상태(`CouponController`의 관리자 revoke API). 사용 여부와 무관하게 `ISSUED`/`USED` 둘 다에서 전이 가능하며, 이후 어떤 상태로도 전이 불가 |
| `EXPIRED` | **종단 상태.** 유효기간(`valid_until`)이 지나 `CouponExpirationBatchJob`이 자동으로 처리한 상태. 이후 어떤 상태로도 전이 불가 |

**전이별 의미**

- `ISSUED → USED` : 사용자가 결제 시 쿠폰을 적용
- `ISSUED → CANCELED` : 아직 안 쓴 쿠폰을 관리자가 강제 회수
- `ISSUED → EXPIRED` : 유효기간이 지나도록 안 쓴 쿠폰을 만료 배치가 자동 처리
- `USED → CANCELED` : 이미 사용한 쿠폰도 관리자가 강제 회수 가능(사용 여부와 무관하게 회수 권한은 유지)
- `USED → ISSUED` : 쿠폰이 적용된 주문을 취소하면, 본인이 그 쿠폰을 다시 쓸 수 있도록 `ISSUED`로 복귀시킨다 (2026-08-13 팀 결정, `OrderService`의 주문취소 처리 중 `markReturnedToIssued` 호출)
- `USED → EXPIRED` : **의도적으로 불허.** `ISSUED`로 복귀시킨 뒤 유효기간이 지났으면 만료 배치가 알아서 처리하므로, `USED`에서 직접 `EXPIRED`로 보내는 별도 경로는 불필요

**상태전이 API 구현 완료** (`CouponIssueService.markUsed/markCanceled/markReturnedToIssued`):

- `markUsed` — `OrderService`가 결제완료(`POST /api/orders`) 처리 중 내부 호출
- `markReturnedToIssued` — `OrderService`가 주문취소(`PATCH /api/orders/{id}/cancel`) 처리 중 내부 호출
- `markCanceled` — `CouponController`의 관리자 강제회수(`POST /api/admin/coupons/{issueId}/revoke`)에서 호출
- 동시 상태전이 요청은 `@Version`(낙관적 락) + `@Retryable(retryFor = {ConcurrencyFailureException, DataIntegrityViolationException}, maxAttempts = 3)`로 지수 백오프 재시도 (자세한 경위는 [트러블슈팅 ⑤](#8-트러블슈팅) 참고)

---

### 6-3. Idempotency 설계

> **구현 완료** — 아래는 확정된 설계이자 실제 적용된 구현입니다 (`docs/planning/04_아키텍처.txt` 5절).

- **발급**: 클라이언트가 매 요청마다 `Idempotency-Key` 헤더를 전송, `coupon_issue.idempotency_key`의 UNIQUE 제약으로 동일 키 재요청을 DB가 거부. 재요청 시 `201`이 아닌 `200`과 기존 발급 결과를 그대로 반환(k6 리허설 스펙으로 검증 완료). Redis 도입 전인 V1.0에서는 비관적 락(row lock)이 재고 확보를 담당.
- **상태전이(사용/취소/만료)**: 호출측(`OrderService`)이 재시도 시에도 동일하게 넘기는 `requestId`를 `coupon_state_log`의 `uk_state_log_request` UNIQUE 제약으로 걸어 동일 요청의 중복 처리를 DB 레벨에서 차단. `@Version`(낙관적 락)과 유니크 제약 경합 모두 `@Retryable`로 최대 3회 지수 백오프 재시도.
- 동일 `requestId`로 100개 동시 재전송하는 통합테스트(`CouponIssueServiceConcurrencyTest`)로, 예외 없이 전부 성공하고 상태전이 로그는 정확히 1건만 남는지 검증했습니다.

---

### 6-4. 멤버십 등급 시스템

회원은 이등병(PRIVATE)·일병(PFC)·상병(CORPORAL)·병장(SERGEANT) 4단계 등급을 가지며, 완료 주문 수 기준으로 매월 1일 자동 재산정됩니다. `MembershipTierBatchJob`으로 구현·검증 완료했습니다.

| 등급 | 완료 주문 수 | 쿠폰 할인율(RATE 타입 기준) |
|---|---|---|
| 이등병 (PRIVATE) | 0~2건 | 10% |
| 일병 (PFC) | 3~10건 | 10% |
| 상병 (CORPORAL) | 11~30건 | 30% |
| 병장 (SERGEANT) | 31건 이상 | 50% |

발급 시점 등급을 스냅샷으로 저장하므로, 이후 등급이 바뀌어도 이미 발급된 쿠폰의 할인율은 불변입니다. 등급이 실제로 바뀐 유저만 `membership_tier_log`에 기록해(전원 기록 시 실행마다 유저 수만큼 로그가 쌓임) 감사 이력을 남깁니다.

로컬 100만 유저 규모로 전체 배치를 실행해 등급 분포(이등병 40만/일병 30만/상병 20만/병장 10만)가 정확히 일치함을 검증했습니다.

---

### 6-5. 정합성 자기검증

> **검증 SQL 구현·실행 완료**, Spring Batch 자동화는 Phase 2 선택 확장 — 아래는 확정된 설계입니다 (`docs/planning/05_시스템설계.txt` 1절).

300만 건 전체를 대상으로, `NOW()` 등 실행 시점에 의존하지 않는 **결정론적** 검증 쿼리 5종(파일 7개)을 `api/src/main/resources/sql/verification/`에 작성해 실제 데이터로 실행 완료했습니다: 재고 초과발급 검증, 재고-이력 카운터 대사, 상태전이 위반 검증(3개 쿼리), 멤버십 등급 eligibility 검증(발급 시점 스냅샷 기준, 현재 등급 기준으로 비교하면 false positive 발생), 계급-주문 집계 일치 검증. **전 항목 0 rows 확인**(폴더 [README](api/src/main/resources/sql/verification/README.md) 참고). 1인 1매(중복 발급)는 `uk_campaign_user` DB 유니크 제약으로 INSERT 단계에서 원천 차단되어 별도 검증 쿼리 대상에서 제외했고, idempotency 위반은 별도 통합테스트로 검증합니다.

현재는 MySQL 클라이언트로 수동 실행하며, `Step` 단위 Spring Batch Job(`ConsistencyVerificationJob`)으로 자동화하고 오염 데이터(초과발급/카운터불일치/상태역행/등급위반)를 의도적으로 삽입해 검증 배치가 실제로 위반을 탐지하는지 증명하는 것은 Phase 2 선택 확장입니다.

---

### 6-6. 더미데이터 파이프라인

과제 요구사항(가상 유저 100만 명 + 발급이력 300만 건)을 실제로 생성·적재하는 5단계 시더 체인을 구현·검증 완료했습니다.

```
UserSeedRunner → OrderSeedRunner → MembershipTierSeedRunner → CampaignSeedRunner → CouponIssueSeedRunner
  (유저 100만)     (등급분포 역산       (등급 재산정 배치        (캠페인 15개 +          (캠페인별 eligibility를
                   주문 1,025만건)      실행)                    쿠폰, 총재고 300만)      만족하는 유저에게 발급)
```

- **대량 INSERT**: `rewriteBatchedStatements=true` + `JdbcTemplate.batchUpdate()` 청크(5,000건)로 다건 INSERT를 하나의 `VALUES (a),(b),(c)...`로 묶어 네트워크 왕복을 최소화.
- **재개 가능한 시더**: `CouponIssueSeedRunner`는 캠페인마다 즉시 커밋되어, 중간에 프로세스가 죽어도 이미 처리된 캠페인은 안전하게 남고 재실행 시 이어서 진행.
- 로컬 100만 유저 / 오더 1,025만 건 / 캠페인 15개(총재고 300만) / 발급이력 약 287만 건 규모로 전체 파이프라인을 검증했습니다(15개 캠페인 중 4개는 의도적으로 재고 일부만 소진시켜 완판/진행중 상태가 섞이도록 구성).

---

## 7. 인프라 & 배포

```
로컬 개발 (local 프로필)
   └─ Docker MySQL 컨테이너
팀 공유 개발 (remote 프로필)
   └─ Tailscale로 연결되는 학원 공용 서버(MySQL)
```

- Docker Compose 구성(MySQL + Redis 한 번에 기동)은 추후 작성 — 현재는 `docker run` 단일 명령으로 로컬 MySQL만 기동.
- GitHub Actions로 `main` 브랜치 push 시 Docker 이미지를 빌드해 GHCR에 푸시.
- 별도 클라우드 프로덕션 배포는 하지 않고, 로컬 및 팀 공유 환경에서 개발·검증합니다.

---

## 8. 트러블슈팅

### ① 등급 재산정 배치의 날짜 파라미터 바인딩 버그

**문제**: `MembershipTierBatchJob`이 완료 주문을 집계할 때, 특정 등급(상병/병장) 구간의 유저가 전혀 산정되지 않고 항상 이등병으로만 남는 문제 발생.
**원인**: 대상 월 범위(`completed_at >= ? AND completed_at < ?`)를 raw `PreparedStatementSetter`로 직접 바인딩했는데, 이 환경에서 `LocalDateTime` 파라미터가 제대로 바인딩되지 않아 조건이 사실상 항상 거짓으로 평가됨.
**해결**: `jdbcTemplate.query(sql, rowMapper, args...)` 형태(Spring의 `StatementCreatorUtils`를 타는 안전한 경로)로 교체.

### ② 대량 UPDATE가 예상보다 훨씬 오래 걸림

**문제**: 유저 100만 명의 등급을 재산정하는 UPDATE가 몇 시간이 걸릴 것으로 예상되는 상황이 됨(`SHOW FULL PROCESSLIST`로 확인 결과 행 단위 UPDATE가 순차 실행 중).
**원인**: `rewriteBatchedStatements=true`는 INSERT처럼 여러 문장을 하나의 `VALUES (...)`로 묶을 수 있을 때만 적용되고, 행마다 `WHERE` 조건이 다른 UPDATE에는 동일한 rewrite가 불가능함.
**해결**: `UPDATE users SET membership_tier = CASE id WHEN ? THEN ? ... END WHERE id IN (...)` 형태로 1,000행씩 청크 묶어서 실행하도록 변경.

### ③ 코드를 고쳤는데 동작이 그대로임

**문제**: `MembershipTierBatchJob`의 버그를 고쳐 재실행했는데도 원격 환경에서 고친 동작이 반영되지 않음.
**원인**: Maven incremental compile이 변경된 소스 파일의 재컴파일을 건너뛰고 이전 class 파일을 그대로 사용.
**해결**: 코드 수정 후에는 `mvnw.cmd clean compile -pl api -am`로 강제 재컴파일 후 실행하는 것을 습관화.

### ④ 발급이력 시더가 예상보다 훨씬 느림

**문제**: 캠페인 15개, 총 300만 건 규모의 `coupon_issue` 시더가 캠페인 하나당 약 5분씩 걸림 — 같은 규모의 오더 1,025만 건이 7분에 끝난 것과 비교하면 비정상적으로 느림.
**원인**: 발급 코드당 `UUID.randomUUID()`를 2회(쿠폰코드, idempotency key) 호출했는데, 내부적으로 `SecureRandom`을 사용해 대량 생성 시 눈에 띄게 느림(특히 Windows 환경).
**해결**: 암호학적 안전성이 필요 없는 시드 데이터 특성을 고려해, `ThreadLocalRandom`으로 128비트를 직접 채워 `UUID`를 구성하는 방식으로 교체.

### ⑤ 동일 requestId 동시 재전송 시 9%가 500 에러

**문제**: 상태전이 동시성 통합테스트(`동일_requestId로_100개_동시요청해도_전부_성공하고_상태전이는_한번만`)에서 100개 중 약 9개가 500 에러로 실패.
**원인**: `markUsed`/`markCanceled`/`markReturnedToIssued`의 `@Retryable`이 낙관적 락 충돌(`ConcurrencyFailureException`)만 재시도 대상으로 잡고 있었음. 그런데 `existsByRequestId` 체크와 `save()` 사이의 race window에서 여러 스레드가 동시에 통과하면 `coupon_state_log.uk_state_log_request` 유니크 제약 위반(`DataIntegrityViolationException`)이 발생하는데, 이 예외는 재시도 대상이 아니어서 그대로 실패 응답이 나감.
**해결**: `retryFor`에 `DataIntegrityViolationException`을 추가([PR #42](https://github.com/Mealiver-IT/Mealiver-IT-BE/pull/42)). 재시도 시 이미 처리된 요청이므로 최신 상태 기준으로 재판정되어 멱등하게 성공 처리됨.

### ⑥ GitHub "Squash and merge"로 dev/main 히스토리가 반복적으로 갈라짐

**문제**: `dev`를 `main`으로 병합하는 PR을 열 때마다 "Can't automatically merge"가 반복 발생 — 한 번 해소해도 다음 PR에서 똑같이 재발.
**원인**: `git cat-file -p <merge-sha>`로 직접 커밋 오브젝트를 열어보니 parent가 1개뿐 — GitHub의 **"Squash and merge"**가 원본 커밋들을 요약한 새 커밋 하나만 만들고, 원본 SHA는 히스토리에서 사라지는 방식이었음. 이 때문에 `dev`와 `main`이 파일 내용은 같아도 git 입장에서는 공통 조상(merge-base)이 계속 어긋나는 서로 다른 히스토리로 보임 — 병합할 때마다 재발하는 구조적 문제.
**부수 발견**: merge-base가 어긋나면 3-way merge가 **파일 리네임/삭제를 놓치는 경우**도 있음 — `git ls-files`(현재 트리)와 `git ls-tree -r origin/dev --name-only`(목표 트리)를 `comm`으로 비교해, 이름이 바뀐 뒤에도 구버전 파일이 orphan으로 남아있는 걸 찾아 정리.
**해결**: 병합 시 **"Create a merge commit"**을 선택하도록 통일(원본 SHA를 부모로 보존하는 방식이라 재발하지 않음). `git merge --no-commit --no-ff`로 로컬에서 먼저 충돌을 전부 검토·해소한 뒤 커밋·푸시.

---

> **태진아 Team**
