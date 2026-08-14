# 쿠폰 발급 부하테스트 스크립트 — Phase 2 (인프라/부하테스트 담당)

발급 API + 상태전이(주문) API의 **idempotency(멱등성)**를 검증하는 k6 스크립트 모음입니다. "같은 요청이 네트워크 재시도·중복 클릭 등으로 여러 번 도달해도, 실제 처리는 정확히 1회만 일어나는가"를 확인합니다.

Phase 1(스모크 테스트 + 소규모 리허설)은 `K6/phase1/`, Phase 3(대규모 동시요청 + Redis + 대시보드)는 `K6/phase3/`에 있습니다.

## 실행 환경

- k6 설치 필요 (`k6 run <파일명>.js`)
- `BASE_URL`은 실제 서버(`http://100.125.247.64:8080`)로 세팅되어 있음

## 파일 목록

| 파일 | 용도 | 상태 |
|---|---|---|
| `phase2-idempotency.js` | 발급 API — 같은 유저+같은 Idempotency-Key로 100개 동시 요청 | 완료, 100/100 (1건 신규발급 + 99건 이미처리됨) |
| `phase2b-state-idempotency.js` | 상태전이 API — 같은 주문 취소 요청 100개 동시 전송 | 완료, 100/100 (버그 발견→이진희님 수정→3차 재검증 후 통과) |

## ⚠️ 실행 전 필수 — 사전 데이터 준비

**두 스크립트 다 실행 전에 대상 데이터(캠페인/쿠폰/주문)를 먼저 만들어야 하고, 그 결과로 나온 ID를 스크립트 상단에 직접 넣어야 합니다.** (아직 스크립트가 자동으로 데이터를 준비하지 않음 — 추후 `setup()`으로 자동화 예정)

**`phase2-idempotency.js` 사전 준비:**
```bash
# 1. 캠페인 생성
POST /api/campaigns  {"name": "...", "totalStock": 10, "discountType": "FIXED", "discountValue": 1000, "validHours": 24}

# 2. 캠페인 OPEN
PATCH /api/campaigns/{id}/status  {"status": "OPEN"}

# 3. 스크립트의 CAMPAIGN_ID를 위 id로 수정 후 실행
```

**`phase2b-state-idempotency.js` 사전 준비:**
```bash
# 1~2. 위와 동일하게 캠페인 생성 + OPEN
# 3. 쿠폰 발급
POST /api/campaigns/{id}/coupons  헤더: X-User-Id, Idempotency-Key

# 4. 주문 생성 (쿠폰을 USED 상태로 전환)
POST /api/orders  헤더: X-User-Id, Idempotency-Key  바디: {"orderAmount":10000,"paidAmount":9000,"couponIssueId": 발급받은쿠폰ID}

# 5. 스크립트의 ORDER_ID, COUPON_ISSUE_ID를 위 결과로 수정 후 실행
```

## 주문(Order) API 스펙 (소스 확인 완료)

```
POST /api/orders
헤더: X-User-Id, Idempotency-Key
바디: { orderAmount, paidAmount, couponIssueId(nullable) }
→ 성공 시 쿠폰이 USED로 전이

PATCH /api/orders/{orderId}/cancel
헤더: Idempotency-Key
바디(선택): { couponIssueId }
→ 성공 시 쿠폰이 다시 ISSUED로 전이
```

## 실행 시 주의

- 발급 API와 마찬가지로 `userId`는 숫자 문자열이어야 함
- 같은 주문 ID로 재실행하면 이미 취소된 주문이라 결과가 달라질 수 있음 — 재실행하려면 위 사전 준비 과정을 새 캠페인/쿠폰/주문으로 반복해야 함

## 관련 폴더

- `K6/phase1/` — 스모크 테스트, 소규모 정확성 리허설
- `K6/phase3/` — 대규모 동시요청, Redis 장애주입, 실시간 대시보드