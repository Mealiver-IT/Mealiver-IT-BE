/**
 * retry_mix 시나리오 — 동일 유저 5,000명 × 4회 재요청
 *
 * 같은 유저 N명이 각자 같은 Idempotency-Key로 4번씩 재요청을 보냄 (재시도 사이
 * RETRY_BACKOFF만큼 대기, 기본 1초 — 첫 요청이 이미 커밋된 뒤에 재시도가 도착).
 * 확인 포인트: ① 1인 1매(4번 요청해도 발급은 1건만) ② 멱등성(재시도 응답이 최초
 * 발급분과 같은 쿠폰을 가리켜야 함).
 *
 * API_MODE 두 가지 지원 (계약이 서로 다름):
 *   - stub : server.js 스텁 서버. body {userId}, 응답은 랜덤 200/409.
 *   - real : Mealiver-IT-BE 실제 API. body 없음, 헤더 X-User-Id / Idempotency-Key,
 *            응답은 200(멱등 재응답)/201(신규 발급)/409(SOLD_OUT 등)/403/400.
 *
 * 실행 방법:
 *   k6 run -e API_MODE=real -e BASE_URL=http://<서버>:8080 \
 *     -e CAMPAIGN_ID=<campaignId> -e USER_ID_BASE=<안 쓴 범위> -e RETRY_USERS=5000 retry_mix.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const API_MODE = __ENV.API_MODE || 'stub'; // 'stub' | 'real'
const BASE_URL = __ENV.BASE_URL || 'http://localhost:3000';
const CAMPAIGN_ID = __ENV.CAMPAIGN_ID || '1';
const USER_ID_BASE = Number(__ENV.USER_ID_BASE || 900000);

const RAMP_UP = __ENV.RAMP_UP || '15s';
const RETRY_USERS = Number(__ENV.RETRY_USERS || 5000);
const RETRY_ATTEMPTS = Number(__ENV.RETRY_ATTEMPTS || 4);
const RETRY_BACKOFF = Number(__ENV.RETRY_BACKOFF || 1); // 재시도 사이 대기(초)
const RETRY_HOLD = __ENV.RETRY_HOLD || '20s';
const RETRY_RAMPDOWN = __ENV.RETRY_RAMPDOWN || '10s';

const retrySuccess = new Counter('retry_issue_success'); // 200/201
const retryFail409 = new Counter('retry_issue_fail_409');
const retryUnexpected = new Counter('retry_issue_unexpected');
// real 모드: 같은 유저의 재시도 응답들이 서로 다른 쿠폰(id)을 가리키거나, 두 번째
// 이후 응답도 201(신규 생성)이면 "진짜 멱등성 버그"로 간주.
const retryDuplicateIssue = new Rate('retry_duplicate_issue_rate');

// VU별로 지속되는 상태 (모듈 스코프 = k6가 VU마다 독립된 JS 런타임으로 실행하므로 VU 간 공유 안 됨)
const __VU_STATE__ = { firstCouponId: undefined };

if (API_MODE === 'real') {
  http.setResponseCallback(http.expectedStatuses(200, 201, 409, 403, 400));
} else {
  http.setResponseCallback(http.expectedStatuses(200, 409));
}

export const options = {
  scenarios: {
    retry_mix: {
      executor: 'ramping-vus',
      exec: 'retryScenario',
      startVUs: 0,
      stages: [
        { duration: RAMP_UP, target: RETRY_USERS },
        { duration: RETRY_HOLD, target: RETRY_USERS },
        { duration: RETRY_RAMPDOWN, target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    checks: ['rate>0.95'],
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000'],
    retry_duplicate_issue_rate: ['rate<0.01'], // 재시도 중 "진짜" 중복 발급은 1% 미만이어야 함
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

function extractCouponId(res) {
  if (API_MODE !== 'real') return undefined;
  try {
    return res.json().data.id;
  } catch (e) {
    return undefined;
  }
}

export function retryScenario() {
  const userId = API_MODE === 'real' ? USER_ID_BASE + __VU : `retry-user-${__VU}`;
  // 매 재시도마다 "같은" Idempotency-Key를 보냄 -> 진짜 재시도 흉내
  const idempotencyKey = `retry-${userId}-fixed-key`;

  if (__ITER >= RETRY_ATTEMPTS) {
    sleep(3600);
    return;
  }

  const res = issueCoupon(userId, idempotencyKey);

  const ok = check(res, {
    'retry: status is expected': (r) =>
      isSuccessStatus(r.status) || r.status === 409 || r.status === 403 || r.status === 400,
  });
  if (!ok) {
    console.error(`[retry_mix] 예상 밖 응답: status=${res.status}, body=${res.body}`);
  }

  if (isSuccessStatus(res.status)) {
    retrySuccess.add(1);

    if (API_MODE === 'real') {
      const couponId = extractCouponId(res);
      if (__VU_STATE__.firstCouponId === undefined) {
        __VU_STATE__.firstCouponId = couponId;
        retryDuplicateIssue.add(res.status === 200 && __ITER > 0);
      } else {
        const isRealDuplicateBug =
          couponId !== __VU_STATE__.firstCouponId || res.status === 201;
        retryDuplicateIssue.add(isRealDuplicateBug);
      }
    } else {
      if (__VU_STATE__.firstCouponId === undefined) {
        __VU_STATE__.firstCouponId = true;
        retryDuplicateIssue.add(false);
      } else {
        retryDuplicateIssue.add(true);
      }
    }
  } else if (res.status === 409) {
    retryFail409.add(1);
  } else {
    retryUnexpected.add(1);
  }

  sleep(RETRY_BACKOFF);
}

export function handleSummary(data) {
  const round = __ENV.ROUND_LABEL || 'adhoc';
  console.log(`=== [retry_mix/${round}] (API_MODE=${API_MODE}) 테스트 요약 ===`);
  console.log(`총 요청 수: ${data.metrics.http_reqs.values.count}`);
  console.log(`평균 응답시간: ${data.metrics.http_req_duration.values.avg.toFixed(2)}ms`);
  console.log(`성공(200/201): ${data.metrics.retry_issue_success ? data.metrics.retry_issue_success.values.count : 0}`);
  console.log(`실패(409): ${data.metrics.retry_issue_fail_409 ? data.metrics.retry_issue_fail_409.values.count : 0}`);
  return { stdout: `${JSON.stringify(data.metrics, null, 2)}\n` };
}
