#!/usr/bin/env bash
# race.js 또는 retry_mix.js 를 한 회차(round) 실행하고, 결과/로그/대시보드를 시나리오
# 전용 폴더에 정리한다. race와 retry_mix는 항상 따로따로 돌린다.
#
# 사용법:
#   ./run-round.sh <round-label> <race-vus> [base-url]
#
# 예시:
#   ./run-round.sh race-10k 10000                       # race만 (RETRY_USERS=0 기본)
#   RACE_VUS=0 RETRY_USERS=5000 ./run-round.sh retry-5k 0  # retry_mix만
set -euo pipefail

ROUND="${1:?사용법: run-round.sh <round-label> <race-vus> [base-url]}"
RACE_VUS="${2:?사용법: run-round.sh <round-label> <race-vus> [base-url]}"
BASE_URL="${3:-http://localhost:3000}"
API_MODE="${API_MODE:-stub}"          # stub | real
CAMPAIGN_ID="${CAMPAIGN_ID:-1}"
USER_ID_BASE="${USER_ID_BASE:-900000}" # API_MODE=real일 때만 사용
RAMP_UP="${RAMP_UP:-15s}"
RETRY_USERS="${RETRY_USERS:-0}"
RETRY_ATTEMPTS="${RETRY_ATTEMPTS:-4}"
RETRY_BACKOFF="${RETRY_BACKOFF:-1}"

if [ "$RACE_VUS" -gt 0 ] && [ "$RETRY_USERS" -gt 0 ]; then
  echo "race와 retry_mix는 같이 돌리지 않습니다. RACE_VUS 또는 RETRY_USERS 둘 중 하나만 0보다 크게 지정하세요." >&2
  echo "  race만: RACE_VUS=10000 RETRY_USERS=0 ./run-round.sh <round> 10000" >&2
  echo "  retry_mix만: RACE_VUS=0 RETRY_USERS=5000 ./run-round.sh <round> 0" >&2
  exit 1
fi

if [ "$RACE_VUS" -gt 0 ]; then
  CATEGORY="race"
  SCRIPT_NAME="race.js"
elif [ "$RETRY_USERS" -gt 0 ]; then
  CATEGORY="retry_mix"
  SCRIPT_NAME="retry_mix.js"
else
  echo "RACE_VUS와 RETRY_USERS가 둘 다 0입니다. 둘 중 하나는 0보다 커야 합니다." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGS_DIR="$SCRIPT_DIR/$CATEGORY/logs"
DASHBOARDS_DIR="$SCRIPT_DIR/$CATEGORY/dashboards"
mkdir -p "$LOGS_DIR" "$DASHBOARDS_DIR"

SUMMARY_PATH="$LOGS_DIR/$ROUND.summary.json"
DASHBOARD_PATH="$DASHBOARDS_DIR/$ROUND.html"
LOG_PATH="$LOGS_DIR/$ROUND.log"

echo "=== [$CATEGORY/$ROUND] $SCRIPT_NAME 실행 (RACE_VUS=$RACE_VUS, RETRY_USERS=$RETRY_USERS, RAMP_UP=$RAMP_UP) ==="
echo "로그       : $LOG_PATH"
echo "대시보드   : $DASHBOARD_PATH"

K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT="$DASHBOARD_PATH" \
  k6 run \
    --summary-export="$SUMMARY_PATH" \
    -e API_MODE="$API_MODE" \
    -e BASE_URL="$BASE_URL" \
    -e CAMPAIGN_ID="$CAMPAIGN_ID" \
    -e USER_ID_BASE="$USER_ID_BASE" \
    -e RAMP_UP="$RAMP_UP" \
    -e RACE_VUS="$RACE_VUS" \
    -e RETRY_USERS="$RETRY_USERS" \
    -e RETRY_ATTEMPTS="$RETRY_ATTEMPTS" \
    -e RETRY_BACKOFF="$RETRY_BACKOFF" \
    -e ROUND_LABEL="$ROUND" \
    "$SCRIPT_DIR/$CATEGORY/$SCRIPT_NAME" 2>&1 | tee "$LOG_PATH"

echo ""
echo "=== [$CATEGORY/$ROUND] 완료 ==="
echo "  로그       : $LOG_PATH"
echo "  summary    : $SUMMARY_PATH"
echo "  대시보드   : $DASHBOARD_PATH"
