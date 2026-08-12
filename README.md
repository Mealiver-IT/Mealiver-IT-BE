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
9. [AI 협업 개발 방식](#9-ai-협업-개발-방식)

---

## 1. 프로젝트 소개

### 배경

U+ 백엔드 과제 "대규모 트래픽 선착순 쿠폰 발급 시스템"을, **배민 스타일 배달앱에서 매일 오전 11시 정각에 여는 오픈런 할인쿠폰** 시나리오로 구체화한 프로젝트입니다. 회원가입/로그인은 구현하지 않고 가상 회원 데이터로 대체합니다.

핵심 요구사항은 명확합니다 — 재고 10,000장에 20,000명이 동시에 요청해도 **초과 발급 0건, 1인 최대 1매**. 그리고 발급/사용/취소/만료 이력 300만 건 전체에 대해, 같은 데이터로 재실행하면 같은 결과가 나오는 **결정론적 정합성 자기검증**.

### 목표

| 목표 | 해결 기술 |
|---|---|
| 재고 10,000장·동시요청 20,000건에도 초과발급 0건, 1인 1매 | DB unique 제약 + 비관적 락(MVP) → Redis 이중 카운터(하드닝, 설계 확정) |
| 300만 건 발급이력 전체에 대한 결정론적 정합성 자기검증 | 재실행 시 동일 결과가 나오는 검증 SQL 6종 + Spring Batch (설계 완료) |
| 회원 등급별 차등 혜택 (이등병~병장 4단계) | 완료 주문 수 기준 매월 1일 자동 재산정 배치 |
| 100만 유저·300만 발급이력 규모 실증 | 청크 배치 시더 + `rewriteBatchedStatements` 기반 대량 적재 파이프라인 |

### 프로젝트 기간

```
2026.08.06 ~ (2주 / LG 부트캠프 멘토링 2회, 발표 및 시상 있음)
```

---

## 2. 팀원 소개

> 추후 작성 (팀 역할 최종 확정 후 업데이트)

| 역할 | 이름 | 담당 도메인 |
|---|---|---|
| 트랙 A — 발급 API / 동시성 | | `CouponIssueService`, 상태전이 API, 동시성 전략(비관적 락 → Redis 이중 카운터) |
| 트랙 B — 데이터 / 검증배치 | 윤태형 | 더미데이터 시더(유저/오더/등급/캠페인/발급이력), `MembershipTierBatchJob`, 정합성 검증 배치 |
| 트랙 C — 인프라 / 부하테스트 / 프론트 | | Docker Compose, k6 부하테스트, 이벤트/결제 페이지 |

---

## 3. 기술 스택

### Backend

| 분류 | 기술 |
|---|---|
| 언어 / 프레임워크 | ![Java](https://img.shields.io/badge/Java-21-007396?style=for-the-badge&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white) ![Maven](https://img.shields.io/badge/Maven-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white) |
| ORM | ![Spring Data JPA](https://img.shields.io/badge/Spring%20Data%20JPA-59666C?style=for-the-badge&logo=hibernate&logoColor=white) |
| 배치 | Spring `@Scheduled` (등급 재산정), Spring Batch (정합성 검증 리포트 — 설계 완료, 구현 예정) |
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

### 협업 도구

| 분류 | 도구 |
|---|---|
| AI 개발 도구 | ![Claude](https://img.shields.io/badge/Claude-AI%20Pair%20Programming-D97757?style=for-the-badge&logo=anthropic&logoColor=white) |

---

## 4. 시스템 아키텍처

```
[클라이언트(웹, 이벤트·결제 페이지만 실제 동작 / 나머지는 정적 mockup)]
        │ REST
        ▼
[Spring Boot Application]
   ├─ Controller / Service 계층 (발급·캠페인 API)   ── 추후 작성
   ├─ Batch    : MembershipTierBatchJob (매월 1일 등급 재산정)
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

> 설계 완료, 구현 예정 — 아래는 확정된 설계입니다 (`docs/planning/04_아키텍처.txt` 4절).

재고 초과 방지를 위한 6가지 전략을 비교한 뒤, **3단계 버전사다리**로 가기로 확정했습니다.

| 전략 | 정합성 | 처리량 | 인프라 의존성 |
|---|---|---|---|
| (a) DB unique + 비관적 락 | 강함 (row lock 완전 직렬화) | 낮음 (hot row 경합) | MySQL만 |
| (c) Redis Lua script 게이트 | 강함 | 높음 | Redis 필수 |
| (f) Redis 이중 카운터 (`countReq`/`count` 분리) | 강함 (총 발급량이 재고를 절대 못 넘음이 증명됨) | 높음, Lua 대비 오버헤드 낮음 | Redis 필수 |

**확정 로드맵**: `V1.0 MVP = (a) 비관적 락` → `V2.0 = (c) Redis Lua (검토 후 대체)` → `V2.1 최종 채택 = (f) Redis 이중 카운터`.

Redis가 상태를 잃는 경우(강제 종료 후 재시작)에 대비해 방어선 2겹을 추가로 둡니다: 발급 트랜잭션 안에서 실행되는 **DB 조건부 UPDATE 백스톱**(`UPDATE campaign SET remaining_stock = remaining_stock - 1 WHERE id = ? AND remaining_stock > 0`)과, 앱 기동/Redis 복구 시 DB 실제 발급 수를 기준으로 Redis 카운터를 재동기화하는 **멱등한 워밍업 함수**입니다.

---

### 6-2. 쿠폰 상태 머신

`ISSUED → USED / CANCELED / EXPIRED`, 역행 불가 상태전이는 거부됩니다. 허용 전이 목록은 엔티티 레벨에 구현·테스트 완료되어 있습니다.

```java
public enum CouponStatus {
    ISSUED, USED, CANCELED, EXPIRED;

    private static final Map<CouponStatus, Set<CouponStatus>> TRANSITIONS = Map.of(
            ISSUED, Set.of(USED, CANCELED, EXPIRED),
            USED, Set.of(CANCELED),   // 사용 후 취소(환불)만 허용, 그 외 역행 불가
            CANCELED, Set.of(),
            EXPIRED, Set.of()
    );

    public boolean canTransitionTo(CouponStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
```

상태전이 API 자체(`CouponController`, `CouponIssueService`)는 추후 작성입니다.

---

### 6-3. Idempotency 설계

> 설계 완료, 구현 예정 — 아래는 확정된 설계입니다 (`docs/planning/04_아키텍처.txt` 5절).

- **발급**: 클라이언트가 매 요청마다 `Idempotency-Key`를 전송, `coupon_issue.idempotency_key`의 UNIQUE 제약으로 동일 키 재요청을 DB가 거부. 재고 확보(Redis) → DB 조건부 UPDATE 백스톱 → INSERT 순서로, `DataIntegrityViolationException` 발생 시 재고를 원복하고 기존 레코드를 그대로 반환.
- **상태전이(사용/취소/만료)**: `request_id` 기준으로 `coupon_state_log`에 동일 요청 처리 여부를 먼저 확인. `@Version`(낙관적 락)으로 동시 상태변경 요청 중 하나만 성공시키고, 나머지는 `spring-retry`로 재시도 후 최신 상태 기준 재판정.

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

> 설계 완료, 구현 예정 (Phase 2) — 아래는 확정된 설계입니다 (`docs/planning/05_시스템설계.txt` 1절).

300만 건 전체를 대상으로, `NOW()` 등 실행 시점에 의존하지 않는 **결정론적** 검증 쿼리 6종을 설계했습니다: 재고-이력 대사, 1인 1매(중복 발급) 검증, 상태전이 위반 검증, 멤버십 등급 eligibility 검증(발급 시점 스냅샷 기준, 현재 등급 기준으로 비교하면 false positive 발생), 등 6가지를 `Step` 단위 Spring Batch Job(`ConsistencyVerificationJob`)으로 이원화할 계획입니다. 오염 데이터(초과발급/상태역행/중복발급/idempotency 위반/등급위반)를 의도적으로 삽입해 검증 배치가 실제로 위반을 탐지하는지 증명하는 것까지 포함됩니다.

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

---

## 9. AI 협업 개발 방식

이번 프로젝트의 데이터/검증배치 파트는 **Claude**(Cowork)를 페어 프로그래밍 형태로 활용해 개발했습니다.

- `MembershipTierBatchJob`, 5단계 더미데이터 시더 체인 등 배치/시더 코드의 설계·구현
- 성능 이슈 발견 시 `SHOW FULL PROCESSLIST` 등 실측 근거를 함께 확인하며 원인 진단 및 수정 (위 트러블슈팅 ①~④)
- 로컬 100만 유저 / 리모트 환경 각각에서의 검증, PR 설명·문서(본 README 포함) 작성

AI가 제안한 코드는 실제 실행 로그·쿼리 결과로 검증한 뒤에만 반영했습니다.

---

> **태진아 Team**
