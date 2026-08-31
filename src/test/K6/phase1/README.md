# 쿠폰 발급 부하테스트 스크립트 — Phase 1 (인프라/부하테스트 담당)

밀리버릿 쿠폰 발급 API(`CampaignController`, `CouponClaimController`)를 대상으로 하는 Phase 1(환경 스모크 테스트 + 소규모 정확성 리허설) k6 스크립트 모음입니다. 실제 서버가 없던 초기에는 스텁 서버로 문법만 연습했었는데, 지금은 실제 서버로 전부 교체됐고 스텁 서버 코드는 삭제했습니다.

이 폴더는 BE 레포 `Mealiver-IT-BE/api/src/test/K6/phase1/`에 있습니다. Phase 2(idempotency 검증)는 `K6/phase2/`, Phase 3(대규모 동시요청 + Redis 장애주입 + 대시보드)는 `K6/phase3/`에 별도로 정리되어 있습니다.

## 실행 환경

- k6 설치 필요 (`k6 run <파일명>.js`)
- 각 스크립트 상단의 `BASE_URL`이 이미 실제 서버(`http://100.125.247.64:8080`)로 세팅되어 있어서 별도 설정 없이 바로 실행 가능합니다.

## 파일 목록

| 파일 | 용도 | 상태 |
|---|---|---|
| `smoke-test.js` | 서버가 정상 동작하는지 기본 확인용 (VU 10명, 1인 1회 발급) | 코드 완료, 실제 서버 대상 실행은 아직 안 함 |
| `phase1-rehearsal.js` | Phase 1 리허설 — 재고100 vs 요청50, 초과발급 없이 전원 발급되는지 확인 | 2026-08-12 실행 완료, 50/50 성공 |

## 발급 API 요청/응답 스펙 (소스 확인 완료)

**캠페인 생성** `POST /api/campaigns` — `CampaignCreateRequest`, 필드 전부 최상위(중첩 X):

```json
{
  "name": "string",
  "totalStock": 100,
  "minMembershipTier": null,
  "discountType": "FIXED",
  "discountValue": 1000,
  "minOrderAmount": null,
  "maxDiscountAmount": null,
  "validHours": 24
}
```

`totalStock`, `validHours`는 둘 다 int 필수 — 하나라도 빠지면 `500 Cannot map null into type int`로 원인 파악이 어렵게 실패합니다.

**쿠폰 발급** `POST /api/campaigns/{id}/coupons`
- 헤더: `X-User-Id`(숫자, `Long` 파싱), `Idempotency-Key`
- 바디 없음
- 응답 코드:
  - `201` 정상 발급
  - `200` 이미 발급된 유저가 재요청 (`ALREADY_PROCESSED`, 에러 아님)
  - `409` 재고 소진 (`SOLD_OUT`)일 때만
  - `400` 존재하지 않는 `X-User-Id`

**캠페인은 생성 직후 `READY` 상태라 바로 발급이 안 됩니다** — 발급 가능하게 하려면 먼저 열어야 합니다:

```
PATCH /api/campaigns/{id}/status
{"status": "OPEN"}
```

## users_20000.json 재생성

`api/users_20000.json`(k6 부하테스트용 고정 유저 20,000명 목록)은 `.gitignore`에 걸려 있어 커밋되지 않습니다. 필요하면 아래 스크립트로 로컬에서 바로 재생성하세요.

```bash
python api/src/test/K6/phase1/generate_users_20000.py
```

출력 필드:

| 필드 | 용도 |
|---|---|
| `id` | **`X-User-Id` 헤더에 이 값을 써야 함** (숫자, `Long` 파싱 필수 — 위 스펙 참고) |
| `loginId` | 참고/로깅용 (`users.login_id`, API에는 안 씀) |
| `idempotencyKey` | `Idempotency-Key` 헤더 |

`loginId="userN"` → `id=N+1` 매핑을 가정합니다(100만 유저 시더가 `users` 테이블에 가장 먼저, 순서대로 넣기 때문). **이 가정이 실제와 다를 수 있어서(시딩 순서가 바뀌었거나, 로컬/리모트가 다르게 리셋됐거나) 스크립트가 파일을 쓰기 전에 매번 실제 DB에 대고 자동으로 확인합니다** — `user0`/`user{count-1}`의 실제 `id`를 조회해서 예상과 다르면 파일을 쓰지 않고 에러로 중단합니다.

- 기본은 로컬 DB(`docker exec mealiver-mysql`)로 검증합니다. Docker Desktop이 떠 있어야 합니다.
- 리모트로 검증하려면: `python generate_users_20000.py --host 100.125.247.64 --port 3306 --user <팀 채널에서 확인> --password <팀 채널에서 확인> --database mealiver` (계정 정보는 절대 커밋하지 말 것)
- Docker가 없어서 검증을 못 하는 환경이면 `--skip-verify`로 건너뛸 수 있습니다(이 경우 매핑이 틀렸을 수도 있다는 걸 감수하는 것).

## 실행 시 주의

- `userId`는 반드시 숫자 문자열(`${__VU}` 등)로 보내야 합니다 — `user-1-0` 같은 비숫자 값은 `Long` 파싱에서 400으로 깨집니다.
- 같은 캠페인에 같은 유저는 한 번만 발급 가능합니다(`uk_campaign_user` 제약). 리허설 스크립트를 재실행하려면 새 캠페인을 만들거나, 기존 캠페인의 `coupon_issue` 로우를 DB에서 지워야 합니다.
- DB 직접 삭제가 필요하면 API에 `DELETE`가 없으므로, `coupon_state_log` → `coupon_issue` → `coupon` → `campaign` 순서로 지워야 외래키 에러가 안 납니다.

## 관련 폴더

- `K6/phase2/` — 발급/상태전이 API idempotency 검증 (100회 동시 중복요청 테스트)
- `K6/phase3/` — 10,000~20,000명 대규모 동시요청, Redis 장애주입 데모, 실시간 대시보드