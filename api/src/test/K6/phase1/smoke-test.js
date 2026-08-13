/**
 * 쿠폰 발급 API 부하테스트 - 기본 뼈대 (Smoke Test)
 *
 * 목적: k6 문법(VU, iterations, check)에 익숙해지기 위한 연습용 스크립트
 * 대상: 실제 발급 API 서버 (100.125.247.64:8080)
 *
 * 실행 방법:
 *   k6 run smoke-test.js
 *
 * 캠페인 생성 요청 필드 (CampaignCreateRequest, BE 소스 확인 완료):
 *   name, totalStock(int), minMembershipTier, discountType, discountValue,
 *   minOrderAmount, maxDiscountAmount, validHours(int) — 전부 최상위 필드 (중첩 X)
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// ── 설정 (여기만 바꾸면 다른 서버/시나리오에 재사용 가능) ──────────────
const BASE_URL = 'http://100.125.247.64:8080';
const CAMPAIGN_ID = 17; // smoke-test-campaign, totalStock=100 (2026-08-12 생성)

// ── 커스텀 메트릭 (k6 기본 제공 지표 외에 우리가 직접 추가로 세고 싶은 것들) ──
// 나중에 "초과발급 0건" 같은 걸 확인할 때, 성공/실패 건수를 직접 세는 용도로 활용 가능
const successCount = new Counter('coupon_issue_success');
const failCount = new Counter('coupon_issue_fail');
const failRate = new Rate('coupon_issue_fail_rate');

// ── 테스트 옵션 (오늘은 아주 작게 - 문법 연습용) ────────────────────
// 나중에 Phase 3에서는 vus: 20000 같은 식으로 크게 키우면 됨
//
// vus+duration(반복 실행) 대신 per-vu-iterations를 쓰는 이유: userId가 VU당 고정값(${__VU})이라
// 같은 유저가 반복 요청하면 uk_campaign_user 위반 → ALREADY_PROCESSED(HTTP 200)로 응답한다.
// 이건 409가 아니라서 아래 check/threshold를 다 깨뜨린다 - VU당 정확히 1회만 요청해야 함
// (phase1-rehearsal.js와 동일 패턴).
export const options = {
  scenarios: {
    smoke: {
      executor: 'per-vu-iterations',
      vus: 10,
      iterations: 1,
      maxDuration: '10s',
    },
  },

  // 통과/실패 기준선 (thresholds) - 이 조건을 못 넘으면 k6가 최종적으로 FAIL 표시해줌
  thresholds: {
    http_req_duration: ['p(95)<500'], // 95%의 요청이 500ms 안에 끝나야 함
    coupon_issue_fail_rate: ['rate<0.5'], // 실패율이 50% 미만이어야 함 (재고 100/요청 10 소규모라 대부분 성공해야 정상)
  },
};

// ── 각 가상유저가 반복 실행하는 시나리오 ────────────────────────────
export default function () {
  // __VU: 현재 몇 번째 가상유저인지 (k6가 자동으로 넣어주는 값)
  // 시드된 유저 100만 명 중 1~10번 사용 (실존 유저로 확인됨, phase1-rehearsal.js와 동일 패턴)
  const userId = `${__VU}`;

  const params = {
    headers: {
      'X-User-Id': userId,
      'Idempotency-Key': `${userId}-${Date.now()}`,
    },
  };

  const res = http.post(
    `${BASE_URL}/api/campaigns/${CAMPAIGN_ID}/coupons`,
    null,
    params
  );

  // check(): 응답이 기대한 대로 왔는지 검증 (통과/실패 개수를 k6가 자동 집계)
  // VU당 1회, 서로 다른 유저 10명, 재고 100 >> 요청 10이라 전원 201이 기대값.
  // (참고: 중복 발급 시도는 409가 아니라 200 OK - ALREADY_PROCESSED - 로 응답한다.
  //  409는 진짜 품절(SOLD_OUT)일 때만 나온다. 이 스모크 테스트는 그 케이스를 만들지 않는다.)
  const isSuccess = check(res, {
    'status is 201': (r) => r.status === 201,
    'response has body': (r) => r.body && r.body.length > 0,
  });

  if (!isSuccess) {
    console.error(`예상 밖 응답: status=${res.status}, body=${res.body}`);
  }

  // 커스텀 메트릭 집계
  if (res.status === 201) {
    successCount.add(1);
    failRate.add(false);
  } else {
    failCount.add(1);
    failRate.add(true);
  }
}

// ── 테스트 종료 후 요약 출력 (선택사항) ─────────────────────────────
export function handleSummary(data) {
  console.log('=== 테스트 요약 ===');
  console.log(`총 요청 수: ${data.metrics.http_reqs.values.count}`);
  console.log(`평균 응답시간: ${data.metrics.http_req_duration.values.avg.toFixed(2)}ms`);

  return {
    stdout: JSON.stringify(data, null, 2), // 콘솔에도 전체 JSON 출력하고 싶으면 유지, 아니면 삭제 가능
  };
}
