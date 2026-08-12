/**
 * Phase 3 - 20,000명 동시요청 시나리오 (coupon_race.js)
 *
 * 확정된 조건: 테스트 유저 20,000명 (중복 없음), ramp-up 60초
 *   - shared-iterations 방식(30초, 램프업 없음) 대신 ramping-vus로 60초에 걸쳐
 *     점진적으로 20,000명까지 늘어나는 방식으로 변경 (실제 유저 유입 패턴 흉내)
 *
 * 대상 캠페인: id=20 "phase3-coupon-race-20k" (totalStock=20000)
 *   재고=요청 수(20,000)로 맞춰서, 이번 실행은 "전원 정상 발급" 시연용.
 *   (재고<요청으로 초과발급 방지를 증명하는 케이스는 별도 캠페인으로 재고를 낮춰서 재실행)
 *
 * 주의: ramping-vus executor는 원래 "VU가 stage 동안 계속 반복 요청"하는 방식이라,
 *       "유저 1명 = 요청 1번"을 지키기 위해 VU별로 1회 요청 후에는
 *       더 이상 요청하지 않도록 모듈 스코프 플래그(hasIssued)로 막아둠.
 *
 * 실행 방법:
 *   k6 run coupon_race.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = 'http://100.125.247.64:8080';
const CAMPAIGN_ID = 20;

const successCount = new Counter('coupon_issue_success');
const failCount = new Counter('coupon_issue_fail');

export const options = {
  scenarios: {
    ramp_20k_users: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '60s', target: 20000 }, // 60초에 걸쳐 0 -> 20,000명 점진적 유입
        { duration: '10s', target: 20000 }, // 막차 VU들도 1회씩 요청할 시간 확보
      ],
      gracefulRampDown: '30s',
      gracefulStop: '60s',
    },
  },
  // 20,000명 전원이 정확히 1번씩만 요청하는지 (재고=요청이라 전원 성공이 기대값)
  thresholds: {
    coupon_issue_success: ['count==20000'],
  },
};

// VU 안에서 유지되는 상태 (모듈 스코프 = VU마다 독립적으로 초기화됨)
let hasIssued = false;

export default function () {
  if (hasIssued) {
    // 이 VU는 이미 1회 요청 완료 -> 남은 시간 동안 추가 요청 안 함
    sleep(1);
    return;
  }
  hasIssued = true;

  // __VU: 1~20000, 시드된 유저 100만 명 범위 내라 중복 없이 안전하게 사용 가능
  const userId = `${__VU}`;

  const params = {
    headers: {
      'X-User-Id': userId,
      'Idempotency-Key': `coupon-race-${userId}-${Date.now()}`,
    },
  };

  const res = http.post(
    `${BASE_URL}/api/campaigns/${CAMPAIGN_ID}/coupons`,
    null,
    params
  );

  const isSuccess = check(res, {
    'status is 201': (r) => r.status === 201,
  });

  if (isSuccess) {
    successCount.add(1);
  } else {
    failCount.add(1);
    console.error(`발급 실패: VU=${__VU}, status=${res.status}, body=${res.body}`);
  }
}

export function handleSummary(data) {
  console.log('=== 20,000명 동시요청 결과 ===');
  console.log(`성공: ${data.metrics.coupon_issue_success ? data.metrics.coupon_issue_success.values.count : 0}`);
  console.log(`실패: ${data.metrics.coupon_issue_fail ? data.metrics.coupon_issue_fail.values.count : 0}`);

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
