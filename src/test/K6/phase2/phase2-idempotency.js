/**
 * Phase 2 - Idempotency 검증 (동일 요청 100회 동시 재전송)
 *
 * 목적: 완전히 똑같은 요청(같은 유저, 같은 Idempotency-Key)이 100번 동시에
 *       들어와도, 실제 발급은 딱 1건만 일어나고 나머지 99건은 "이미 처리됨"
 *       응답만 받는지 확인 (중복발급 0건 증명)
 *
 * 기대 결과: 201(신규 발급) 1건 + 200(ALREADY_PROCESSED) 99건
 *           재고는 정확히 1개만 차감되어야 함
 *
 * 대상 캠페인: id=21 "phase2-idempotency-test" (totalStock=10)
 * 실행 방법:   k6 run phase2-idempotency.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = 'http://100.125.247.64:8080';
const CAMPAIGN_ID = 21;

// 100명 전부 "같은 사람"인 척, "같은 요청"을 보내는 게 이 테스트의 핵심
const FIXED_USER_ID = '1';
const FIXED_IDEMPOTENCY_KEY = 'phase2-fixed-key-2026-08-12';

const createdCount = new Counter('created_201');
const alreadyProcessedCount = new Counter('already_processed_200');
const unexpectedCount = new Counter('unexpected_status');

export const options = {
  scenarios: {
    duplicate_burst: {
      executor: 'per-vu-iterations',
      vus: 100,
      iterations: 1,
      maxDuration: '20s',
    },
  },
  thresholds: {
    created_201: ['count==1'],           // 신규 발급은 딱 1건이어야 함
    already_processed_200: ['count==99'], // 나머지 99건은 이미 처리됨 응답
    unexpected_status: ['count==0'],      // 400/409/500 등은 하나도 없어야 함
  },
};

export default function () {
  const params = {
    headers: {
      'X-User-Id': FIXED_USER_ID,
      'Idempotency-Key': FIXED_IDEMPOTENCY_KEY, // 100명 전원 동일한 키 사용
    },
  };

  const res = http.post(
    `${BASE_URL}/api/campaigns/${CAMPAIGN_ID}/coupons`,
    null,
    params
  );

  const isExpected = check(res, {
    'status is 201 or 200': (r) => r.status === 201 || r.status === 200,
  });

  if (!isExpected) {
    unexpectedCount.add(1);
    console.error(`예상 밖 응답: VU=${__VU}, status=${res.status}, body=${res.body}`);
  } else if (res.status === 201) {
    createdCount.add(1);
  } else {
    alreadyProcessedCount.add(1);
  }
}

export function handleSummary(data) {
  const created = data.metrics.created_201 ? data.metrics.created_201.values.count : 0;
  const already = data.metrics.already_processed_200 ? data.metrics.already_processed_200.values.count : 0;
  const unexpected = data.metrics.unexpected_status ? data.metrics.unexpected_status.values.count : 0;

  console.log('=== Phase 2 Idempotency 결과 ===');
  console.log(`신규 발급(201): ${created}건 (기대값: 1)`);
  console.log(`이미 처리됨(200): ${already}건 (기대값: 99)`);
  console.log(`예상 밖 응답: ${unexpected}건 (기대값: 0)`);

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
