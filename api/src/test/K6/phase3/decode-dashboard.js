// k6 웹 대시보드 export(dashboard.html)에 내장된 원본 시계열 데이터를 디코딩해서
// 10초(=k6 web dashboard 기본 period) 간격 표로 뽑아낸다. dashboard.html의
// <script id="data" type="application/json; ...; gzip; base64"> 안에 NDJSON이
// gzip+base64로 들어있고, 각 메트릭의 snapshot/cumulative 값은 이름 배열이 아니라
// "그 시점까지 등록된 메트릭 이름을 알파벳순 정렬한 위치"의 배열로 들어있어서,
// summary.json의 확정 수치와 대조해서 인덱스 매핑을 검증한 뒤 작성함.
//
// 사용법: node decode-dashboard.js <dashboard.html> [성공카운터_메트릭이름]
//   예: node decode-dashboard.js retry_mix/dashboards/retry-5kx4-run1.html retry_issue_success
//       node decode-dashboard.js race/dashboards/race-10kstock-20kreq.html race_issue_success
const fs = require('fs');
const zlib = require('zlib');

const htmlPath = process.argv[2];
const successMetricArg = process.argv[3];
const content = fs.readFileSync(htmlPath, 'utf-8');
const m = content.match(/<script id="data"[^>]*>([^<]+)<\/script>/);
if (!m) { console.error('데이터 스크립트를 못 찾음'); process.exit(1); }
const json = zlib.gunzipSync(Buffer.from(m[1], 'base64')).toString('utf-8');
const lines = json.trim().split('\n').map(l => JSON.parse(l));

const param = lines.find(l => l.event === 'param').data;
const startEvent = lines.find(l => l.event === 'start');
const startTs = startEvent.data[0][0];

// 메트릭이 스트림 도중에 여러 번(누적) 선언될 수 있음 -> 그때그때 시점의 메트릭 집합으로
// 알파벳순 인덱스를 다시 계산해서, 그 시점 이후의 snapshot에 적용해야 함.
let metricDefs = {};
let idx = {};
function rebuildIdx() {
  const names = Object.keys(metricDefs).sort();
  idx = Object.fromEntries(names.map((n, i) => [n, i]));
}

function get(snapData, name, i) {
  if (idx[name] === undefined) return undefined;
  const arr = snapData[idx[name]];
  return arr && arr.length > i ? arr[i] : undefined;
}

const rows = [];
let cumReqs = 0, cumSuccess = 0;
for (const l of lines) {
  if (l.event === 'metric') {
    Object.assign(metricDefs, l.data);
    rebuildIdx();
    continue;
  }
  if (l.event !== 'snapshot') continue;
  const d = l.data;
  const t = get(d, 'time', 0);
  const relSec = t ? Math.round((t - startTs) / 1000) : '?';
  const vus = get(d, 'vus', 0) ?? '-';
  const reqCount = get(d, 'http_reqs', 0) ?? 0;
  cumReqs += reqCount;
  const durAvg = get(d, 'http_req_duration', 0);
  const durP95 = get(d, 'http_req_duration', 5);
  const failRate = get(d, 'http_req_failed', 0);
  const successCount = successMetricArg ? (get(d, successMetricArg, 0) ?? 0) : 0;
  cumSuccess += successCount;
  const durStr = durAvg !== undefined ? `${durAvg.toFixed(1)}ms / ${durP95.toFixed(1)}ms` : '-';
  const failStr = failRate !== undefined ? `${(failRate*100).toFixed(1)}%` : '-';
  rows.push(`| ${relSec}s | ${vus} | ${reqCount} | ${cumReqs} | ${durStr} | ${failStr} | ${cumSuccess} |`);
}

const successLabel = successMetricArg ? `${successMetricArg}(누적)` : '성공(누적)';
console.log(`| 구간(초) | VU | 요청수(창내) | 누적요청수 | http_req_duration avg/p95 | http_req_failed | ${successLabel} |`);
console.log('|---|---|---|---|---|---|---|');
rows.forEach(r => console.log(r));
