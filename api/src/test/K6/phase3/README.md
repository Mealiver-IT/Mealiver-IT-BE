# Phase 11 — 쿠폰 발급 부하테스트

BE/Infra 체크리스트 항목 두 개를 각 시나리오 폴더 안의 독립 스크립트로 구현했습니다.

- [x] 10,000 vs 20,000 동시요청 k6 시나리오 (`race/race.js`)
- [x] 동일 유저 5,000명 × 4회 재요청 시나리오 (`retry_mix/retry_mix.js`)

두 스크립트는 원래 한 파일(`options.scenarios`에 둘 다 등록)이었는데, **항상 하나씩만
켜서 따로 실행**하는 쪽으로 굳어지면서 아예 시나리오별 독립 파일로 쪼갰습니다. 초기에는
두 시나리오를 동시에 켜서 한 번에 돌려본 적도 있었는데, 실패 원인이 race 때문인지
retry_mix 때문인지 구분이 안 돼서 분리 실행으로 정리했습니다 (자세한 경위는
[`RESULTS.md`](RESULTS.md) 참고). `run-round.sh`/`run-round.ps1`도 `RACE_VUS`/`RETRY_USERS`
둘 다 0보다 크게 주면 바로 에러를 내도록 되어 있습니다.

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

## 실행 방법

### 방법 A — 회차별로 자동 정리 (권장)

Windows / PowerShell:
```powershell
cd phase11
./run-round.ps1 -Round "race-10k" -RaceVus 10000                    # race만
./run-round.ps1 -Round "retry-5k" -RaceVus 0 -RetryUsers 5000       # retry_mix만
```

Git Bash / macOS / Linux:
```bash
cd phase11
./run-round.sh race-10k 10000                                    # race만
RACE_VUS=0 RETRY_USERS=5000 ./run-round.sh retry-5k 0             # retry_mix만
```

**회차 폴더는 시나리오별로 정확히 둘 중 하나에만 들어갑니다** — `race/` 또는
`retry_mix/`, 그 외 폴더는 없습니다. 각 폴더 안은 phase3 `campaign_*` 폴더와 같은
방식으로 `logs/`/`dashboards/` 둘로만 나뉘고, 회차 이름이 파일명이 됩니다.

```
phase11/
  decode-dashboard.js        # dashboard.html에서 10초 간격 시계열 뽑는 공용 유틸
  race/
    race.js                   # race 시나리오 스크립트 (독립 실행형)
    logs/
      <round>.log             # k6 콘솔 출력 전체 로그
      <round>.summary.json    # --summary-export 결과 (수치 비교용)
    dashboards/
      <round>.html            # k6 웹 대시보드 export (그래프 포함, 브라우저로 열어서 확인)
  retry_mix/
    retry_mix.js               # retry_mix 시나리오 스크립트 (독립 실행형)
    logs/  ...
    dashboards/  ...
  concurrent_duplicate/
    concurrent_duplicate.js    # concurrent_duplicate 시나리오 스크립트
    logs/  ...
    dashboards/  ...
```

각 시나리오 스크립트는 서로 독립적입니다 (예전엔 `race`/`retry_mix`가 한 파일을 공유했는데,
시나리오 폴더 안에 그 시나리오가 쓰는 스크립트가 바로 보이는 게 낫다는 판단으로 쪼갰습니다
— 공통 헬퍼 코드는 약간 중복되지만 폴더 하나만 보면 실행/결과가 전부 완결됩니다).

시나리오별 결과는 각 폴더의 README를 보세요 ([`race/README.md`](race/README.md),
[`retry_mix/README.md`](retry_mix/README.md),
[`concurrent_duplicate/README.md`](concurrent_duplicate/README.md)).

### 방법 B — 직접 실행

```bash
k6 run -e RACE_VUS=10000 race/race.js
k6 run -e RACE_VUS=20000 race/race.js
k6 run -e RETRY_USERS=5000 retry_mix/retry_mix.js
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
  **backoff 없이 진짜 동시에** 4번씩 중복 요청 (체크리스트 항목은 아니고, phase3가 찾아낸
  "중복요청 락 증폭 버그" 수정이 지금도 안전한지 확인하는 보너스 회귀 테스트)

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

## 참고

- 이 문서/스크립트는 원래 저장소 루트의 `phase3` 폴더와는 무관하게 독립적으로 작성됐습니다. 다만 이후 결과를 정리하며 비교해보니(`RESULTS.md` 참고) 같은 결론(초과발급 0건)에 도달했고, retry_mix가 phase3 쪽에서 발견/수정된 "동시 중복요청 락 증폭 버그"를 재현 안 하는 이유(1초 backoff로 레이스 컨디션 자체가 안 생김)도 확인했습니다.
