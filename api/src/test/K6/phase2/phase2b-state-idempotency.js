/**
 * Phase 2 (나머지 절반) - 상태전이 Idempotency 검증
 *
 * 목적: 같은 주문 취소 요청(같은 Idempotency-Key)이 100번 동시에 들어와도,
 *       쿠폰 상태전이(USED -> ISSUED)가 딱 한 번만 일어나는지 확인
 *
 * 발급 API와 다른 점: 발급은 DB unique 제약 위반을 catch해서 복구하는 방식이지만,
 * 상태전이(markReturnedToIssued)는 @Version(낙관적 락) 충돌 시 @Retryable로
 * 최대 3회 자동 재시도 -> 재시도 중 이미 처리된 요청이면 조용히 스킵하는 방식.
 * 그래서 기대값은 "1건만 200, 99건은 409" 같은 게 아니라 "100건 전부 200,
 * 500(에러)은 0건이어야 함" -> 재시도 메커니즘이 충돌을 완전히 흡수하는지가 핵심.
 *
 * 사전 준비 (curl로 완료):
 *   - 캠페인 23, 쿠폰 3199346 발급 후 주문 10249897 생성 (쿠폰 USED 상태)
 *
 * 실행 방법:
 *   k6 run phase2b-state-idempotency.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = 'http://100.125.247.64:8080';
const ORDER_ID = 10249905;
const COUPON_ISSUE_ID = 3200116;

// 100명 전원이 "같은 취소 요청"을 보내는 게 핵심 (같은 Idempotency-Key)
const FIXED_IDEMPOTENCY_KEY = 'p2b-cancel-fixed-key-2026-08-14-verifyfix4';

const successCount = new Counter('cancel_success_200');
const errorCount = new Counter('cancel_unexpected');

export const options = {
  scenarios: {
    duplicate_cancel_burst: {
      executor: 'per-vu-iterations',
      vus: 100,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    cancel_success_200: ['count==100'], // 전원 200 (재시도로 흡수되어야 함)
    cancel_unexpected: ['count==0'],     // 500 등 에러가 하나도 없어야 함
  },
};

export default function () {
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': FIXED_IDEMPOTENCY_KEY, // 100명 전원 동일
    },
  };

  const res = http.patch(
    `${BASE_URL}/api/orders/${ORDER_ID}/cancel`,
    JSON.stringify({ couponIssueId: COUPON_ISSUE_ID }),
    params
  );

  const isSuccess = check(res, {
    'status is 200': (r) => r.status === 200,
  });

  if (isSuccess) {
    successCount.add(1);
  } else {
    errorCount.add(1);
    console.error(`예상 밖 응답: VU=${__VU}, status=${res.status}, body=${res.body}`);
  }
}

export function handleSummary(data) {
  const success = data.metrics.cancel_success_200 ? data.metrics.cancel_success_200.values.count : 0;
  const error = data.metrics.cancel_unexpected ? data.metrics.cancel_unexpected.values.count : 0;

  console.log('=== Phase 2b 상태전이 Idempotency 결과 ===');
  console.log(`200 성공: ${success}건 (기대값: 100)`);
  console.log(`예상 밖 에러: ${error}건 (기대값: 0)`);

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
