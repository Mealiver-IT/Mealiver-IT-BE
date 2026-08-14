#!/bin/bash
# ============================================================
# check_zero_anomalies.sh
#
# 하는 일:
#   sql/validation/ 폴더의 각 검증 쿼리를 공유 DB에 대해 실행해서
#   결과가 정말로 0건(ROW 0)인지 확인한다.
#
# check_determinism.sh 와의 차이:
#   - determinism  : "같은 쿼리를 두 번 돌리면 같은 결과가 나오는가" (쿼리 자체의 안정성)
#   - zero_anomaly : "지금 이 순간 실제 데이터에 이상치가 있는가" (데이터의 건강 상태)
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "❌ .env 파일이 없습니다: $ENV_FILE"
  exit 1
fi

set -a
source "$ENV_FILE"
set +a

# DB_URL=jdbc:mysql://HOST:PORT/DBNAME 형태 파싱 (이전에 만든 것과 동일)
if [[ "$DB_URL" =~ jdbc:mysql://([^:/]+):([0-9]+)/([^?]+) ]]; then
  DB_HOST="${BASH_REMATCH[1]}"
  DB_PORT="${BASH_REMATCH[2]}"
  DB_NAME="${BASH_REMATCH[3]}"
else
  echo "❌ DB_URL 형식을 인식하지 못했습니다: $DB_URL"
  exit 1
fi

export MYSQL_PWD="$DB_PASSWORD"
MYSQL_CMD="mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -D $DB_NAME -N -B"

# 공유 DB 안전장치: 쿼리 하나가 60초 넘게 걸리면 강제 종료.
# 300만 건 규모에서 60초 넘긴다는 건 대개 인덱스를 안 타고 있다는 신호라,
# 무한정 붙잡고 있다가 다른 사람 쿼리까지 느려지게 만드는 걸 방지.
MAX_EXECUTION_MS=60000

VALIDATION_DIR="$SCRIPT_DIR/../verification"
FAIL=0

echo "대상 DB: $DB_HOST:$DB_PORT/$DB_NAME"
echo "쿼리당 최대 실행시간: $((MAX_EXECUTION_MS / 1000))초"
echo ""

for qfile in "$VALIDATION_DIR"/*.sql; do
  name=$(basename "$qfile" .sql)


  # 1) '--' 라인 주석은 통째로 제거. 서브쿼리로 감쌀 때 줄바꿈 위치가 밀리면서
  #    주석 안 특수문자/괄호 때문에 MySQL 라인 계산이 꼬여 문법 에러로 이어지는 걸 방지.
  # 2) 세미콜론은 마지막에 남은 것만 제거 (문장 끝을 표시하는 세미콜론)
  # 3) CRLF(\r) 잔재 제거 — Windows에서 작성된 파일에 섞여 있을 수 있음
  query_body=$(grep -v '^[[:space:]]*--' "$qfile" | sed -e 's/;[[:space:]]*$//' -e 's/\r$//')

  echo "▶ $name 실행 중... ($(date '+%H:%M:%S'))"
  start_ts=$(date +%s)

  # SET SESSION MAX_EXECUTION_TIME: 이 세션에서만 적용되는 타임아웃.
  # 다른 사람의 쿼리나 세션에는 영향 없음 — 공유 DB에서 안전하게 쓸 수 있는 옵션.
  # count=$(...) 할당 중 mysql이 에러로 죽으면 set -e 때문에 스크립트 전체가
  # 조용히 종료돼버렸던 문제가 있었음. "|| true"로 실패해도 스크립트가 안 죽게 하고,
  # stderr를 별도 파일로 받아서 실제 에러 메시지를 화면에 보여준다.
  err_file=$(mktemp)
  # "FROM (" 뒤에 명시적으로 줄바꿈을 넣어서 query_body 첫 줄이 같은 물리 라인에 안 붙게 함
  # (전 버전 버그: ${query_body}가 "(" 바로 뒤에 붙어서 첫 줄이 주석과 한 줄로 합쳐졌었음)
  count=$($MYSQL_CMD -e "
    SET SESSION MAX_EXECUTION_TIME=${MAX_EXECUTION_MS};
    SELECT COUNT(*) FROM (
${query_body}
    ) AS anomalies;
  " 2>"$err_file" | tail -1) || true

  if [ -s "$err_file" ]; then
    echo "⚠️  $name: mysql 에러 발생"
    echo "--- 에러 내용 ---"
    cat "$err_file"
    echo "-----------------"
    rm -f "$err_file"
    FAIL=1
    continue
  fi
  rm -f "$err_file"

  end_ts=$(date +%s)
  elapsed=$((end_ts - start_ts))

  # MAX_EXECUTION_TIME 타임아웃 걸리면 mysql이 에러 메시지를 반환하고 count가 숫자가 아니게 됨
  if ! [[ "$count" =~ ^[0-9]+$ ]]; then
    echo "⚠️  $name: 실행 실패 또는 타임아웃 (${elapsed}초 경과)"
    echo "    → EXPLAIN으로 인덱스 사용 여부부터 확인해보세요."
    FAIL=1
    continue
  fi

  if [ "$count" -eq 0 ]; then
    echo "✅ $name: PASS (이상치 0건, ${elapsed}초 소요)"
  else
    echo "❌ $name: FAIL (이상치 ${count}건 발견, ${elapsed}초 소요)"
    FAIL=1
  fi
  echo ""
done

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "🎉 전체 통과: 모든 검증 쿼리에서 이상치 0건."
else
  echo "⚠️  이상치가 발견된 쿼리가 있습니다. 위 로그를 확인하세요."
fi

exit $FAIL