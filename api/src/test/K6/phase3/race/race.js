/**
 * race 시나리오 — 10,000 vs 20,000 동시요청
 *
 * 서로 다른 유저 N명이 각자 쿠폰을 1번씩만 동시에 요청. 재고보다 많은 인원이 몰렸을 때
 * 정확히 재고만큼만 발급되고(초과발급 0건), 나머지는 정상적으로 거절되는지 확인하는
 * 동시성/재고 소진 레이스 컨디션 테스트.
 *
 * API_MODE 두 가지 지원 (계약이 서로 다름):
 *   - stub : server.js 스텁 서버. body {userId}, 응답은 랜덤 200/409.
 *   - real : Mealiver-IT-BE 실제 API. body 없음, 헤더 X-User-Id / Idempotency-Key,
 *            응답은 200(멱등 재응답)/201(신규 발급)/409(SOLD_OUT 등)/403/400.
 *
 * 실행 방법:
 *   k6 run -e API_MODE=real -e BASE_URL=http://<서버>:8080 \
 *     -e CAMPAIGN_ID=<campaignId> -e USER_ID_BASE=<안 쓴 범위> -e RACE_VUS=10000 race.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const API_MODE = __ENV.API_MODE || 'stub'; // 'stub' | 'real'
const BASE_URL = __ENV.BASE_URL || 'http://localhost:3000';
const CAMPAIGN_ID = __ENV.CAMPAIGN_ID || '1';
const USER_ID_BASE = Number(__ENV.USER_ID_BASE || 900000);

const RAMP_UP = __ENV.RAMP_UP || '15s';
const RACE_VUS = Number(__ENV.RACE_VUS || 10000);
const RACE_HOLD = __ENV.RACE_HOLD || '5s';
const RACE_RAMPDOWN = __ENV.RACE_RAMPDOWN || '10s';

const raceSuccess = new Counter('race_issue_success'); // 200/201
const raceFail409 = new Counter('race_issue_fail_409');
const raceUnexpected = new Counter('race_issue_unexpected'); // 403/400/그 외

if (API_MODE === 'real') {
  http.setResponseCallback(http.expectedStatuses(200, 201, 409, 403, 400));
} else {
  http.setResponseCallback(http.expectedStatuses(200, 409));
}

export const options = {
  scenarios: {
    race: {
      executor: 'ramping-vus',
      exec: 'raceScenario',
      startVUs: 0,
      stages: [
        { duration: RAMP_UP, target: RACE_VUS },
        { duration: RACE_HOLD, target: RACE_VUS },
        { duration: RACE_RAMPDOWN, target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    checks: ['rate>0.95'],
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000'],
  },
};

function issueCoupon(userId, idempotencyKey) {
  if (API_MODE === 'real') {
    const params = {
      headers: { 'X-User-Id': String(userId), 'Idempotency-Key': idempotencyKey },
    };
    return http.post(`${BASE_URL}/api/campaigns/${CAMPAIGN_ID}/coupons`, null, params);
  }
  const payload = JSON.stringify({ userId });
  const params = {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
  };
  return http.post(`${BASE_URL}/api/campaigns/${CAMPAIGN_ID}/coupons`, payload, params);
}

function isSuccessStatus(status) {
  return status === 200 || status === 201;
}

export function raceScenario() {
  const userId = API_MODE === 'real' ? USER_ID_BASE + __VU : `race-user-${__VU}`;
  const idempotencyKey = `race-${userId}-${Date.now()}`;

  const res = issueCoupon(userId, idempotencyKey);

  const ok = check(res, {
    'race: status is expected': (r) =>
      isSuccessStatus(r.status) || r.status === 409 || r.status === 403 || r.status === 400,
  });
  if (!ok) {
    console.error(`[race] 예상 밖 응답: status=${res.status}, body=${res.body}`);
  }

  if (isSuccessStatus(res.status)) raceSuccess.add(1);
  else if (res.status === 409) raceFail409.add(1);
  else raceUnexpected.add(1);

  // 유저당 요청은 1회뿐 -> 나머지 시간은 대기만 하고 재요청하지 않음
  sleep(3600);
}

export function handleSummary(data) {
  const round = __ENV.ROUND_LABEL || 'adhoc';
  console.log(`=== [race/${round}] (API_MODE=${API_MODE}) 테스트 요약 ===`);
  console.log(`총 요청 수: ${data.metrics.http_reqs.values.count}`);
  console.log(`평균 응답시간: ${data.metrics.http_req_duration.values.avg.toFixed(2)}ms`);
  console.log(`성공(200/201): ${data.metrics.race_issue_success ? data.metrics.race_issue_success.values.count : 0}`);
  console.log(`실패(409): ${data.metrics.race_issue_fail_409 ? data.metrics.race_issue_fail_409.values.count : 0}`);
  console.log(`그 외(403/400 등): ${data.metrics.race_issue_unexpected ? data.metrics.race_issue_unexpected.values.count : 0}`);
  return { stdout: `${JSON.stringify(data.metrics, null, 2)}\n` };
}
