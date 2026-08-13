/**
 * Phase 1 리허설 - 소규모 정확성 시연 (재고100 vs 요청50)
 *
 * 목적: 재고보다 적은 인원이 몰렸을 때 전부 정상적으로 발급되는지 확인
 *       (재고 100 > 요청 50이므로 전원 201 성공, 초과발급 없음이 기대값)
 *
 * 대상 캠페인: id=19 "phase1-demo-for-screenshot" (totalStock=100)
 * 실행 방법:   k6 run phase1-rehearsal.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = 'http://100.125.247.64:8080';
const CAMPAIGN_ID = 19;

const successCount = new Counter('coupon_issue_success');
const failCount = new Counter('coupon_issue_fail');

// 50명이 정확히 한 번씩만 요청 (VU당 1회, 동시에 시작)
export const options = {
  scenarios: {
    single_burst: {
      executor: 'per-vu-iterations',
      vus: 50,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    coupon_issue_success: ['count==50'], // 전원 성공해야 리허설 통과
  },
};

export default function () {
  // 시드된 유저 100만 명 중 1~50번 사용 (실존 유저로 확인됨)
  const userId = `${__VU}`;

  const params = {
    headers: {
      'X-User-Id': userId,
      'Idempotency-Key': `phase1-rehearsal-${userId}-${Date.now()}`,
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
  console.log('=== Phase 1 리허설 결과 ===');
  console.log(`성공: ${data.metrics.coupon_issue_success ? data.metrics.coupon_issue_success.values.count : 0}`);
  console.log(`실패: ${data.metrics.coupon_issue_fail ? data.metrics.coupon_issue_fail.values.count : 0}`);

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
