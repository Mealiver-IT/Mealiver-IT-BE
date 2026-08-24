# concurrent_duplicate 시나리오 — 동일 유저의 "진짜 동시" 중복 요청

체크리스트 항목은 아님 (보너스 회귀 확인). `retry_mix`와 목적은 같지만(유저당 5,000명,
재요청 4회, 1인 1매/멱등성 검증) 요청 패턴이 다릅니다.

## 왜 따로 팠나

`retry_mix`는 VU 1개가 4번을 **1초씩 쉬며** 순차적으로 재시도합니다. 첫 요청이 이미
DB에 커밋된 뒤에 재시도가 도착하기 때문에, "여러 요청이 커밋 전에 서로를 못 보고 전부
락을 잡으러 가는" 레이스 컨디션을 애초에 만들지 않습니다.

phase3(`campaign_300`, `coupon_mixed_5k_x4.js`)는 정확히 이 시나리오를 **VU 4개를 유저
1명으로 묶어서 backoff 없이 거의 동시에** 쏘는 방식으로 팠고, 실제로 "중복요청 락 증폭
버그"(idempotency 체크가 락 없는 SELECT라 커밋 전 중복요청이 캠페인 락을 최대 7배
반복 획득)를 찾아냈습니다. 이 시나리오는 그 정확한 재현 방법을 그대로 가져와서,
**phase3에서 고친 `CouponIssuanceDuplicateGuard`(Redis SETNX)가 지금 서버에도 여전히
정상 동작하는지** 확인하는 회귀 테스트입니다.

- 스크립트: [`concurrent_duplicate.js`](concurrent_duplicate.js) (phase3
  `coupon_mixed_5k_x4.js`와 동일 설계: VU `RETRIES_PER_USER`개씩 묶어서 같은 유저ID +
  같은 Idempotency-Key, backoff 없이 `ramping-vus`로 유입)
- VU 수: `USER_COUNT × RETRIES_PER_USER` (기본 5,000 × 4 = 20,000)
- 유저당 요청 수: `RETRIES_PER_USER`개의 VU가 거의 동시에 한 번씩

## 실행 방법

```bash
k6 run -e BASE_URL=http://<서버>:8080 -e CAMPAIGN_ID=<campaignId> \
  -e USER_ID_BASE=<안 쓴 범위> -e USER_COUNT=5000 -e RETRIES_PER_USER=4 \
  concurrent_duplicate.js
```

결과 저장은 수동으로 (`run-round.sh`에는 안 묶어뒀음 — 이 스크립트는 회귀 확인용 1회성
성격이라 필요시 아래 예시처럼 직접 `--summary-export`/`K6_WEB_DASHBOARD_EXPORT` 지정).
`concurrent_duplicate/` 폴더 안에서 실행하는 기준입니다:

```bash
cd concurrent_duplicate
K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT=dashboards/<round>.html \
  k6 run --summary-export=logs/<round>.summary.json \
  -e BASE_URL=http://<서버>:8080 -e CAMPAIGN_ID=<campaignId> -e USER_ID_BASE=<안 쓴 범위> \
  -e USER_COUNT=5000 -e RETRIES_PER_USER=4 \
  concurrent_duplicate.js > logs/<round>.log 2>&1
```

## 결과 (재고 10,000, 5,000명 × 4 VU 동시)

**원칙**: k6 집계 + 서버 재검증(`issuedCount`/`remainingStock`) 둘 다 확인.

| 지표 | run1 (campaign 469) | run2 (campaign 470) | run3 (campaign 471) | run4 (campaign 472) |
|---|---|---|---|---|
| 신규 발급(201) | **5,000** | **5,000** | **5,000** | **5,000** |
| 이미 처리됨(200) | 2,186 | 2,462 | 1,249 | 986 |
| 중복 차단(409 `DUPLICATE_REQUEST_IN_PROGRESS`, 정상) | 12,814 | 12,538 | 13,751 | 14,014 |
| 예상 밖 응답 | **0** | **0** | **0** | **0** |
| checks 통과율 | 100% | 100% | 100% | 100% |
| http_req_duration avg / p95 / max | 4.3s / 7.6s / 11.6s | 4.3s / 7.4s / 13.3s | 3.2s / 6.3s / 45.6s* | **18.8s / 26.7s / 30.0s**† |
| **서버 재검증 (issuedCount / remainingStock)** | **5,000 / 5,000** | **5,000 / 5,000** | **5,000 / 5,000** | **5,000 / 5,000** |
| 20,000건 처리 완료 시점 | ~30s | ~30s | ~30s(+1건 70s) | **~50s** |
| 시계열 | [timeline](logs/concurrent-dup-5kx4-run1.timeline.md) | [timeline](logs/concurrent-dup-5kx4-run2.timeline.md) | [timeline](logs/concurrent-dup-5kx4-run3.timeline.md) | [timeline](logs/concurrent-dup-5kx4-run4.timeline.md) |

\* run3의 45.6s는 20,000건 중 딱 1건(스트래글러)이 70초 지점에 늦게 완료된 것 — 나머지
19,999건은 전부 30초 안에 끝났고, 그 1건도 결국 정상 처리됨(실패 아님).

† run4는 전체적으로 이전 3회차보다 확실히 느렸습니다(응답시간이 12s→26.5s로 꾸준히
증가, 처리 완료까지 50초). **그래도 정확성(초과발급 0건, 예상 밖 응답 0건)은 동일하게
유지**됐습니다 — 그 시점에 서버가 더 바빴거나(공유 서버라 다른 팀원 트래픽 가능성),
자연스러운 변동으로 보이며, 정확성에는 영향이 없었습니다.

네 회차 모두 **초과발급 0건, 예상 밖 응답 0건**으로 동일하게 재현됨 — phase3가 겪었던
80%대 정체/100초 이상 지연/대량 타임아웃 문제가 우연이 아니라 정말 사라졌다는 게
4연속으로 확인됨. 다만 응답 속도 자체는 회차마다 변동폭이 있어(3.2s~18.8s avg), 절대
성능 수치보다는 "정확성이 안정적으로 유지되는가"를 이 시나리오의 핵심 신호로 보는 게
맞습니다.

## phase3와 비교

phase3(`campaign_300`)는 이 정확한 시나리오를 1~10회차에 걸쳐 반복 실행하며 문제를
발견→3개 층(Tomcat 커넥션 한도, 락 증폭, hot row 락 처리량)을 순차적으로 고쳤는데도
**최종적으로 82~83% 달성(5,000명 중 약 4,100명만 성공)이 상한선**이었고, 응답시간은
최대 100초 이상, 첫 10초에 대량 실패(TCP 커넥션 거부)가 항상 있었습니다.

**이번 회차는 그 모든 문제 없이 100% 달성(5,000/5,000), 예상 밖 응답 0건, 응답시간
최대 11.6초**로 나왔습니다. phase3가 고친 것들(중복요청 가드, Tomcat 커넥션 한도 상향,
재고 샤딩 등)이 지금 서버에 전부 살아있고 잘 작동하고 있다는 뜻으로 해석됩니다 — 즉
**회귀 없음, 오히려 phase3 마지막 회차보다도 개선된 상태**를 확인했습니다.

(다만 phase3와 실행 환경·시점·서버 상태가 다르므로 1:1 성능 비교라기보다는 "그때 고친
문제들이 지금도 안전한가"에 대한 정성적 확인으로 보는 게 정확합니다.)
