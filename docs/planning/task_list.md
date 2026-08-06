# Task List — 단계별 로드맵 · 팀 역할 · 리스크

관련: [PRD.md](./PRD.md) | [architecture.md](./architecture.md) | [system_design.md](./system_design.md) | [tech_doc.md](./tech_doc.md)

## 1. 단계별 로드맵 (Phased Roadmap)

각 단계는 독립적으로 데모 가능해야 한다.

### Phase 1 — MVP (핵심 기능 + 최소 동시성 제어)
- [ ] 도메인 모델/스키마 확정, unique constraint 적용 ([architecture.md](./architecture.md) 1~2절)
- [ ] 캠페인/쿠폰 CRUD(관리자용, 간단히), 발급 API(동기, **전략 (a) 비관적 락**)
- [ ] 상태전이 API(사용/취소/만료) + 상태 머신 검증
- [ ] 100만 User, 300만 이력(정상 케이스 위주) 더미데이터 적재
- [ ] 정합성 검증 SQL 쿼리 4종 작성 및 수동 실행 확인 ([system_design.md](./system_design.md) 1절)
- **데모**: 소규모 동시요청(예: 100 vs 50 재고)으로 정확성 시연

### Phase 2 — Core (Idempotency + 검증 자동화)
- [ ] Idempotency key 전 구간 적용(발급 + 상태전이)
- [ ] `spring-retry` 낙관적 락 재시도 적용(상태전이)
- [ ] PII 마스킹(로그 컨버터 + 응답 시리얼라이저) 적용
- [ ] Mock 알림 발송 연동
- [ ] 오염 데이터 유형별(초과발급/상태역행/중복발급/idempotency) 삽입 → 검증 배치가 건수·위치까지 탐지하는지 테스트
- [ ] 결정론성 자동 검증 스크립트: 동일 데이터 2회 실행 → 결과 해시/diff 자동 비교
- **데모**: 동일 요청 100회 동시 재전송해도 상태 1회만 변경됨을 시연

### Phase 3 — Edge cases / Hardening (대규모 동시성 + Redis 게이트)
- [ ] **전략 (c) Redis Lua script** 게이트키퍼 도입, DB 비관적 락과 비교 벤치(선택)
- [ ] 10,000 vs 20,000 동시요청 k6 부하테스트 시나리오 완성 및 반복 실행(5회+)
- [ ] Redis-DB 불일치 보상 로직(재고 롤백) 구현 및 장애 주입 테스트(Redis 재시작 등) 라이브 데모
- [ ] Spring Batch 검증 Job으로 승격, `verification_result` 테이블 적재
- **데모**: 20,000 동시요청 → 정확히 10,000건 발급, 검증 배치가 0건 위반 리포트, Redis 장애→복구 시연

### Phase 4 — Optimization / 확장 (선택, 후순위)
- [ ] 오픈 시각 예약, 실시간 대시보드, 검증 리포트 자동화 중 팀이 선택한 항목
- [ ] (선택) 전략 (d) Kafka 비동기 분리 PoC — 시간 여유 시에만
- [ ] 성능 튜닝, 백오피스류(실시간 대시보드, 성능 벤치마크) — 평가 비대상, 후순위

## 1.5 차별화 전략 (동일 주제 4팀 대비, 확정)

1. 오염 데이터 탐지 다양화 — 위반 유형별(초과발급/상태역행/중복발급/idempotency) 개별 시연 (Phase 2)
2. 장애 주입 + 복구 라이브 데모 (Phase 3)
3. 결정론성 자동 증명 — 해시/diff 비교 스크립트 (Phase 2/3)

## 2. 팀 역할 분담 제안 (회의용 초안 — 최종 분담은 팀 회의에서 결정)

| 역할 | 담당 범위 | 주요 산출물 |
|---|---|---|
| **발급 API / 동시성 담당** | 도메인 모델, 발급/상태전이 API, 동시성 전략 (a)→(c) 구현, idempotency 로직 | Coupon 도메인, `CouponIssueService`, Redis Lua script |
| **데이터 / 검증 배치 담당** | 더미데이터 생성기, 검증 SQL/Spring Batch, PII 마스킹 구현 | 데이터 적재 배치, `ConsistencyVerificationJob`, 마스킹 컨버터/시리얼라이저 |
| **인프라 / 부하테스트 담당** | Docker Compose 구성(MySQL/Redis/Kafka), k6 스크립트, CI, Mock 알림 인프라 | `docker-compose.yml`, k6 시나리오, 부하테스트 결과 리포트 |

3인 기준 초안이며, 인원 수/숙련도에 따라 "발급 API"와 "검증 배치"를 더 잘게 쪼개거나, 인프라 담당이 발급 API 담당을 겸할 수 있다.

## 3. 리스크 및 완화 방안

| 리스크 | 영향 | 완화 |
|---|---|---|
| DB 비관적 락(전략 a)이 hot row 경합으로 부하테스트 시 타임아웃 대량 발생 | 데모 중 다수 요청이 5xx로 실패해 보임(정확성엔 문제 없으나 인상이 나쁨) | 락 대기 타임아웃(`innodb_lock_wait_timeout`) 조정 + 실패 시 "품절/재시도 안내" 도메인 에러로 매끄럽게 처리, VU 수를 단계적으로 올려 데모 |
| Redis-DB 정합성 어긋남(전략 c/d) | 검증 배치에서 위반 탐지되나 원인 추적 어려움 | 재고 확보(Redis) 성공/DB insert 실패 시 반드시 보상(Redis 재고 복구) 트랜잭션 로직 필수화, 실패 로그에 상관관계 ID 남김 |
| 300만 건 검증 쿼리가 인덱스 부재로 느려짐 | 평가대상은 아니나 데모 중 대기시간 과다 | `(campaign_id, user_id)`, `campaign_id`, `coupon_issue_id` 등 필요한 인덱스 사전 설계 및 `EXPLAIN`으로 확인 |
| 낙관적 락 재시도 폭증(thundering herd) | 상태전이 API 응답지연 | 재시도 backoff(지수 백오프) 적용, 최대 재시도 초과 시 명확한 409 응답 |
| PII 마스킹 누락 지점(신규 로그 추가 시 정규식이 못 잡는 포맷) | 개인정보 노출 요구사항 위반 | DTO 레벨 강제(엔티티 직접 응답 금지 컨벤션) + 코드리뷰 체크리스트에 "PII 필드는 마스킹 시리얼라이저 필수" 항목 추가 |
| Docker Compose 로컬 자원 부족(MySQL+Redis+Kafka 동시 구동) | 개발 환경 불안정 | Kafka는 하드닝 이후 선택 확장 단계에서만 기동, 기본은 MySQL+Redis만으로 개발 |

## 4. 팀 회의에서 결정해야 할 항목 (Open Decisions)

- [ ] 동시성 제어 전략: MVP=(a), 하드닝=(c) 권장안을 채택할지, 다른 조합으로 갈지 ([architecture.md](./architecture.md) 4절)
- [ ] DB를 MySQL로 확정할지 (현재 가정)
- [ ] 인프라를 로컬 Docker Compose로 확정할지, 클라우드 환경을 쓸지 (현재 가정)
- [ ] 부하테스트 도구(k6/JMeter/자작 클라이언트 중 선택, 예시는 k6로 작성됨)
- [ ] 팀 역할 분담 (2절 초안 기반 확정)
- [ ] 선택 확장 중 어떤 것을 Phase 4에서 시도할지
