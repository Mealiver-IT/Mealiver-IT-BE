/**
 * concurrent_duplicate 시나리오 — 동일 유저의 "진짜 동시" 중복 요청
 *
 * retry_mix와 목적은 같음(유저당 5,000명, 재요청 4회, 1인 1매/멱등성 검증)이지만
 * 요청 패턴이 다릅니다:
 *   - retry_mix : VU 1개가 4번 반복, 매 시도 사이 RETRY_BACKOFF(기본 1초) 대기 (순차 재시도)
 *   - 이 시나리오: VU 4개를 유저 1명으로 묶어서, backoff 없이 거의 동시에 4개를 쏨
 *     (phase3 campaign_300 `coupon_mixed_5k_x4.js`와 동일한 설계)
 *
 * 왜 필요한가: retry_mix는 첫 요청이 이미 커밋된 뒤에 재시도가 도착하므로, "여러 요청이
 * 커밋 전에 서로를 못 보고 전부 락을 잡으러 가는" 레이스 컨디션(phase3가 찾아낸
 * "중복요청 락 증폭 버그")을 애초에 재현하지 않습니다. 이 시나리오는 그 레이스 컨디션을
 * 의도적으로 재현해서, phase3에서 고친 CouponIssuanceDuplicateGuard(Redis SETNX)가
 * 지금 서버에도 여전히 정상 동작하는지 회귀 확인합니다.
 *
 * 실행 방법:
 *   k6 run -e BASE_URL=http://<서버>:8080 -e CAMPAIGN_ID=<campaignId> \
 *     -e USER_ID_BASE=<안 쓴 범위> -e USER_COUNT=5000 -e RETRIES_PER_USER=4 \
 *     concurrent_duplicate.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:3000';
const CAMPAIGN_ID = __ENV.CAMPAIGN_ID || '1';
const USER_ID_BASE = Number(__ENV.USER_ID_BASE || 900000);
const USER_COUNT = Number(__ENV.USER_COUNT || 5000);
const RETRIES_PER_USER = Number(__ENV.RETRIES_PER_USER || 4);
const TOTAL_VUS = USER_COUNT * RETRIES_PER_USER;
const RAMP_UP = __ENV.RAMP_UP || '15s';
const HOLD = __ENV.HOLD || '15s';

const successCount = new Counter('coupon_issue_success'); // 201
const alreadyProcessedCount = new Counter('coupon_issue_already'); // 200
const duplicateBlockedCount = new Counter('coupon_issue_duplicate_blocked'); // 409 DUPLICATE_REQUEST_IN_PROGRESS (정상)
const unexpectedCount = new Counter('coupon_issue_unexpected'); // 그 외 (진짜 이상 응답)

http.setResponseCallback(http.expectedStatuses(200, 201, 403, 409, 400));

export const options = {
  scenarios: {
    concurrent_duplicate: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP_UP, target: TOTAL_VUS },
        { duration: HOLD, target: TOTAL_VUS },
      ],
      gracefulRampDown: '30s',
      gracefulStop: '3m',
    },
  },
  thresholds: {
    coupon_issue_unexpected: ['count>=0'], // 정보성 (진짜 실패는 로그로 직접 확인)
  },
};

let hasIssued = false;

export default function () {
  // VU당 요청은 1회뿐 -> 나머지 시간은 대기만 하고 재요청하지 않음
  // (sleep 없이 return만 하면 VU가 남은 시간 동안 빈 루프를 초당 수십만 번 돌며
  // CPU를 낭비함)
  if (hasIssued) {
    sleep(3600);
    return;
  }
  hasIssued = true;

  // VU 1~N -> user 1, VU N+1~2N -> user 2, ... (N=RETRIES_PER_USER개씩 묶어서 같은 유저로 취급)
  const userId = USER_ID_BASE + Math.ceil(__VU / RETRIES_PER_USER);
  const idempotencyKey = `concurrent-dup-user-${userId}`;

  const res = http.post(
    `${BASE_URL}/api/campaigns/${CAMPAIGN_ID}/coupons`,
    null,
    {
      headers: { 'X-User-Id': String(userId), 'Idempotency-Key': idempotencyKey },
      timeout: '180s',
    }
  );

  const isDuplicateBlocked =
    res.status === 409 &&
    typeof res.body === 'string' &&
    res.body.indexOf('DUPLICATE_REQUEST_IN_PROGRESS') !== -1;

  check(res, {
    'status is 201, 200, or 409(중복차단)': (r) =>
      r.status === 201 || r.status === 200 || isDuplicateBlocked,
  });

  if (res.status === 201) {
    successCount.add(1);
  } else if (res.status === 200) {
    alreadyProcessedCount.add(1);
  } else if (isDuplicateBlocked) {
    duplicateBlockedCount.add(1);
  } else {
    unexpectedCount.add(1);
    console.error(`예상 밖 응답: VU=${__VU}, userId=${userId}, status=${res.status}, body=${res.body}`);
  }
}

export function handleSummary(data) {
  const g = (n) => (data.metrics[n] ? data.metrics[n].values.count : 0);
  console.log('=== concurrent_duplicate 결과 ===');
  console.log(`신규 발급(201): ${g('coupon_issue_success')}건 (기대값: ${USER_COUNT})`);
  console.log(`이미 처리됨(200): ${g('coupon_issue_already')}건`);
  console.log(`중복 차단(409, 정상): ${g('coupon_issue_duplicate_blocked')}건`);
  console.log(`예상 밖 응답: ${g('coupon_issue_unexpected')}건`);
  return { stdout: `${JSON.stringify(data.metrics, null, 2)}\n` };
}
