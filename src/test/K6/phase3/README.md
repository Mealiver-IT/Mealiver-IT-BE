# Phase 3 — 쿠폰 발급 부하테스트

BE/Infra 체크리스트 항목 두 개를 각 시나리오 폴더 안의 독립 스크립트로 구현했습니다.

- [x] 10,000 vs 20,000 동시요청 k6 시나리오 (`race/race.js`)
- [x] 동일 유저 5,000명 × 4회 재요청 시나리오 (`retry_mix/retry_mix.js`)

두 스크립트는 원래 한 파일(`options.scenarios`에 둘 다 등록)이었는데, **항상 하나씩만
켜서 따로 실행**하는 쪽으로 굳어지면서 아예 시나리오별 독립 파일로 쪼갰습니다. 초기에는
두 시나리오를 동시에 켜서 한 번에 돌려본 적도 있었는데, 실패 원인이 race 때문인지
retry_mix 때문인지 구분이 안 돼서 분리 실행으로 정리했습니다 (자세한 경위는
[`RESULTS.md`](RESULTS.md) 참고).

## 시나리오 상세

| | race | retry_mix |
|---|---|---|
| 목적 | 동시성/재고 소진 레이스 컨디션 확인 | 재시도(중복 클릭/타임아웃 재전송) 시 멱등성 확인 |
| VU 수 | `RACE_VUS` (기본 10000, 비교 시 20000) | `RETRY_USERS` (기본 5000) |
| 유저당 요청 수 | 1회 | `RETRY_ATTEMPTS`회 (기본 4회) |
| Idempotency-Key | 매 유저마다 새로 생성 | **같은 유저는 4회 모두 동일한 키** (재시도 흉내) |
| ramp-up | **15초** (`RAMP_UP`) | **15초** (`RAMP_UP`) — 요청대로 두 시나리오 동일 |

`retry_mix`에서 같은 유저가 4번의 시도 중 200(ISSUED)을 **두 번 이상** 받으면
`retry_duplicate_issue_rate` 메트릭이 올라갑니다 — 실제 API가 붙었을 때 멱등성이 깨졌는지
확인하는 지표입니다. (지금 스텁 서버는 상태가 없는 랜덤 응답이라 이 지표 자체보다는
스크립트/문법 검증용으로 먼저 보시면 됩니다.)

## 폴더 구조

```
phase3/
  race/
    race.js                    # race 시나리오 스크립트 (독립 실행형)
  retry_mix/
    retry_mix.js                # retry_mix 시나리오 스크립트 (독립 실행형)
  concurrent_duplicate/
    concurrent_duplicate.js     # concurrent_duplicate 시나리오 스크립트
```

각 시나리오 스크립트는 서로 독립적입니다 (예전엔 `race`/`retry_mix`가 한 파일을 공유했는데,
시나리오 폴더 안에 그 시나리오가 쓰는 스크립트가 바로 보이는 게 낫다는 판단으로 쪼갰습니다
— 공통 헬퍼 코드는 약간 중복되지만 폴더 하나만 보면 실행이 완결됩니다).

실행 결과(로그/summary/대시보드 html)는 저장소에 커밋하지 않았습니다 — 실행할 때마다
새로 생기는 산출물이라 용량만 커지고 리뷰에 도움이 안 된다는 피드백을 반영했습니다
(`.gitignore` 처리). 실제로 실행해서 나온 수치 요약은 각 시나리오 README의 "결과" 절에
남겨뒀습니다.

시나리오별 결과는 각 폴더의 README를 보세요 ([`race/README.md`](race/README.md),
[`retry_mix/README.md`](retry_mix/README.md),
[`concurrent_duplicate/README.md`](concurrent_duplicate/README.md)).

## 실행 방법

```bash
k6 run -e RACE_VUS=10000 race/race.js
k6 run -e RACE_VUS=20000 race/race.js
k6 run -e RETRY_USERS=5000 retry_mix/retry_mix.js
k6 run -e USER_COUNT=5000 -e RETRIES_PER_USER=4 concurrent_duplicate/concurrent_duplicate.js
```

회차별 로그/summary/대시보드를 자동으로 정리해서 저장하고 싶다면, 아래처럼 직접
`--summary-export`/`K6_WEB_DASHBOARD_EXPORT`를 지정하면 됩니다:

```bash
K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT=race/dashboards/<round>.html \
  k6 run --summary-export=race/logs/<round>.summary.json \
  -e RACE_VUS=10000 race/race.js > race/logs/<round>.log 2>&1
```

## 사전 준비

```bash
# 스텁 서버 (repo 루트에서)
npm start
```

- `k6`가 로컬에 설치되어 있어야 합니다 (`k6 version`으로 확인).
- 실제 API가 붙으면 `-e BASE_URL=https://실제주소`만 바꿔서 그대로 재사용하면 됩니다.
- `RACE_VUS=20000` + `RETRY_USERS=5000` 동시 실행은 VU 합계가 25,000까지 올라갑니다.
  로컬 1대에서 돌릴 경우 OS 파일디스크립터/포트 한도에 걸릴 수 있으니, 필요하면
  `ulimit -n` 상향 또는 k6 분산 실행(k6 cloud / 여러 머신)을 고려하세요.

## 주요 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:3000` | 대상 서버 |
| `CAMPAIGN_ID` | `1` | 캠페인 ID |
| `RAMP_UP` | `15s` | 두 시나리오 공통 ramp-up |
| `RACE_VUS` | `10000` | race 시나리오 목표 동시 VU (10000/20000 비교) |
| `RACE_HOLD` | `5s` | race 목표 VU 도달 후 유지 시간 |
| `RETRY_USERS` | `5000` | retry_mix 유저 수 |
| `RETRY_ATTEMPTS` | `4` | 유저당 재요청 횟수 |
| `RETRY_BACKOFF` | `1` (초) | 재시도 사이 대기 |
| `ROUND_LABEL` | `adhoc` | 콘솔 요약 로그에 찍히는 회차 이름 |

## 결과는 시나리오별 README에

race+retry_mix를 같이 돌렸던 초기 회차들(스텁 파이프라인 검증, 실제 API 소규모 검증,
혼합 실행 등)은 **기록/원본 파일을 전부 삭제**했습니다 — 실패 원인이 race 때문인지
retry_mix 때문인지 안 갈리는 문제가 있었고, 이후로는 항상 하나씩만 따로 돌리는 쪽으로
정리했기 때문입니다. 그때 나온 발견(예: 대량 동시 연결 시 로컬 TCP 포트 고갈 문제) 중
지금도 유효한 내용은 [`RESULTS.md`](RESULTS.md)에 경위만 남겨뒀습니다.

**실제 테스트 결과는 시나리오별로 따로 정리되어 있습니다:**

- **[`race/README.md`](race/README.md)** — 10,000 vs 20,000 동시요청 결과 (체크리스트 항목)
- **[`retry_mix/README.md`](retry_mix/README.md)** — 동일 유저 5,000명×4회 재요청 결과 (체크리스트 항목)
- **[`concurrent_duplicate/README.md`](concurrent_duplicate/README.md)** — 동일 유저 5,000명이
  **backoff 없이 진짜 동시에** 4번씩 중복 요청 (체크리스트 항목은 아니고, 이전 실행에서
  찾아낸 "중복요청 락 증폭 버그" 수정이 지금도 안전한지 확인하는 보너스 회귀 테스트)

## API_MODE (stub vs real)

`race.js`/`retry_mix.js` 둘 다 `API_MODE` 환경변수로 두 계약을 다 지원합니다.

| | `API_MODE=stub` (기본값) | `API_MODE=real` |
|---|---|---|
| 대상 | `server.js` 로컬 스텁 | Mealiver-IT-BE 실제 API |
| 유저 식별 | body `{userId}` | 헤더 `X-User-Id: <Long>` |
| Idempotency-Key | 헤더(그냥 echo만 됨) | 헤더(실제 멱등 로직 동작) |
| 성공 상태코드 | 200 | 200(재응답)/201(신규) |
| 실패 상태코드 | 409(랜덤) | 409/403/400(사유별로 다름) |
| 필요 파라미터 | 없음 | `CAMPAIGN_ID`(미리 생성+OPEN), `USER_ID_BASE`(유효 유저 ID 시작값) |

`API_MODE=real`로 돌릴 때 체크리스트:
1. 대상 서버에 이 테스트 전용 캠페인을 만들고 OPEN 상태로 바꿔서 `CAMPAIGN_ID`로 지정
   (기존 운영/공유 캠페인 재고를 건드리지 않기 위함).
2. `X-User-Id`로 쓸 유효한 유저 ID 범위를 소규모 프로브로 먼저 확인하고 `USER_ID_BASE` 지정.
3. 소규모(수백 VU)로 먼저 돌려서 계약이 맞게 동작하는지, 재고 소모량이 기대치와 맞는지 확인.
4. 본 실행 후에는 `PATCH .../status {status:"CLOSED"}`로 테스트 캠페인을 닫아 정리.

## 다른 캠페인 지정해서 실행하기

**스크립트 파일은 전혀 안 건드립니다.** `CAMPAIGN_ID`가 코드에 하드코딩되어 있지 않고
`__ENV.CAMPAIGN_ID`로 읽어오게 되어 있어서, 실행할 때 넘기는 값만 바꾸면 원하는 아무
캠페인이나 대상으로 삼을 수 있습니다.

```bash
k6 run -e API_MODE=real -e CAMPAIGN_ID=원하는아이디 -e USER_ID_BASE=안쓴범위 \
  -e RACE_VUS=10000 race/race.js
```

**단, 기존(특히 실제 운영 중이거나 팀이 같이 보는) 캠페인을 대상으로 하면:**
- 그 캠페인의 **진짜 재고가 실제로 소모**됩니다 — 되돌리는 API가 없습니다.
- 다른 데모/테스트/실제 유저 데이터와 결과가 섞입니다.

그래서 이 문서의 모든 회차는 항상 **테스트 전용 캠페인을 새로 만들어서** (`POST
/api/campaigns` → `PATCH /status {status:"OPEN"}`) 그 위에서만 실행했습니다. 기존
캠페인을 지정해서 돌리고 싶다면, 그게 지금 실제로 쓰이는 중인 캠페인은 아닌지 먼저
확인하는 걸 권장합니다.

## 참고

- 이 작업은 **이전 phase3 부하테스트를 다시 검증하기 위해 만든 것**입니다. 원래 초기
  실행 이력(별도 작업 폴더의 `campaign_299`/`campaign_300`)이 있었는데, 회차 사이에
  실행 환경 자체가 바뀌는 등(예: 새 PC에 WSL2를 새로 설치하고 실행, 물리 환경이 이전
  회차들과 달라짐) 결과를 서로 비교하기 애매한 부분이 있었습니다. 그래서 환경을
  통제한 상태로 같은 시나리오를 다시 돌려서 비교/재검증한 결과가 지금 이 폴더입니다.
- 실제로 결과를 정리하며 비교해보니(`RESULTS.md` 참고) 같은 결론(초과발급 0건)에
  도달했고, retry_mix가 이전 실행에서 발견/수정된 "동시 중복요청 락 증폭 버그"를
  재현 안 하는 이유(1초 backoff로 레이스 컨디션 자체가 안 생김)도 확인했습니다.
