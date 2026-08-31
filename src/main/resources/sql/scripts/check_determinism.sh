#!/bin/bash
# ============================================================
# check_determinism.sh
#
# 하는 일:
#   sql/validation/ 폴더 안의 각 검증 쿼리(.sql 파일 하나당 SELECT 하나)를
#   2번씩 실행해서, 결과가 항상 똑같이 나오는지(=결정론적인지) 확인한다.
#
# 사용법:
#   ./check_determinism.sh
# ============================================================

set -euo pipefail
# set -e  : 명령어 하나라도 실패하면 스크립트 즉시 중단
# set -u  : 선언 안 된 변수 쓰면 에러 (오타 방지)
# set -o pipefail : 파이프(|) 중간에 실패해도 감지

# ------------------------------------------------------------
# 1단계. .env 파일 불러오기
# ------------------------------------------------------------
# .env 안에는 이런 식으로 값이 들어있음:
#   DB_URL=jdbc:mysql://100.125.247.64:3306/mealiver
#   DB_USER=validator
#   DB_PASSWORD=your_password_here
#
# set -a  → 이 밑에서 읽어들이는 변수들을 전부 자동으로 "export"한다.
#            export를 해야 mysql 같은 다른 프로그램이 이 값을 읽을 수 있음.
# set +a  → export 자동 적용을 다시 끔 (안 그러면 이후 모든 변수가 export됨)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "❌ .env 파일이 없습니다: $ENV_FILE"
  echo "   sql/scripts/.env.example 을 복사해서 .env로 만들고 값을 채워주세요."
  exit 1
fi

set -a
source "$ENV_FILE"
set +a

# ------------------------------------------------------------
# 2단계. DB_URL 파싱하기
# ------------------------------------------------------------
# DB_URL 형태: jdbc:mysql://호스트:포트/DB이름
# 예:          jdbc:mysql://100.125.247.64:3306/mealiver
#
# 정규식으로 세 부분을 뜯어낸다:
#   ([^:/]+)   → 콜론(:)이나 슬래시(/) 나오기 전까지 = 호스트
#   ([0-9]+)   → 숫자만 = 포트
#   ([^?]+)    → 물음표(?) 나오기 전까지(쿼리 파라미터 제외) = DB 이름
if [[ "$DB_URL" =~ jdbc:mysql://([^:/]+):([0-9]+)/([^?]+) ]]; then
  DB_HOST="${BASH_REMATCH[1]}"
  DB_PORT="${BASH_REMATCH[2]}"
  DB_NAME="${BASH_REMATCH[3]}"
else
  echo "❌ DB_URL 형식을 인식하지 못했습니다: $DB_URL"
  echo "   기대하는 형식: jdbc:mysql://호스트:포트/DB이름"
  exit 1
fi

echo "접속 정보 확인:"
echo "  HOST = $DB_HOST"
echo "  PORT = $DB_PORT"
echo "  NAME = $DB_NAME"
echo ""

# ------------------------------------------------------------
# 3단계. mysql 접속 명령어 준비
# ------------------------------------------------------------
# -N : 컬럼 이름(헤더) 줄 빼고 데이터만 출력
# -B : 배치 모드 (결과를 탭으로 구분된 깔끔한 텍스트로 출력)
export MYSQL_PWD="$DB_PASSWORD"
# ↑ mysql 프로그램은 MYSQL_PWD 라는 환경변수가 있으면
#   비밀번호 입력 프롬프트 없이 자동으로 그 값을 사용한다.

MYSQL_CMD="mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -D $DB_NAME -N -B"

# ------------------------------------------------------------
# 4단계. 쿼리 파일들을 하나씩 2번 실행 + 결과 비교
# ------------------------------------------------------------
VALIDATION_DIR="$SCRIPT_DIR/../verification"
OUTPUT_DIR="$SCRIPT_DIR/determinism_check"
mkdir -p "$OUTPUT_DIR"

FAIL=0   # 하나라도 실패하면 1로 바꿀 변수

for qfile in "$VALIDATION_DIR"/*.sql; do
  name=$(basename "$qfile" .sql)   # 예: query_a_stock_overflow.sql → query_a_stock_overflow

  # 같은 쿼리를 2번 실행해서 각각 정렬 후 저장
  for run in 1 2; do
    $MYSQL_CMD < "$qfile" | sort > "$OUTPUT_DIR/${name}_run${run}.txt"
    # sort를 하는 이유:
    #   쿼리에 ORDER BY가 없으면 실행할 때마다 행 순서가 달라질 수 있다.
    #   내용은 같은데 순서만 달라서 "다른 결과"로 오판하지 않도록
    #   비교 전에 항상 정렬해서 순서를 통일한다.
  done

  # 두 결과 파일의 SHA-256 해시값 계산
  # 해시값 = 파일 내용을 요약한 고유한 지문 같은 것.
  # 내용이 1byte라도 다르면 해시값이 완전히 달라진다.
  h1=$(sha256sum "$OUTPUT_DIR/${name}_run1.txt" | awk '{print $1}')
  h2=$(sha256sum "$OUTPUT_DIR/${name}_run2.txt" | awk '{print $1}')

  if [ "$h1" == "$h2" ]; then
    echo "✅ $name: PASS (두 번 실행 결과가 완전히 동일함)"
  else
    echo "❌ $name: FAIL (두 번 실행 결과가 다름 → 비결정론적 쿼리 의심)"
    echo "   --- 차이점 ---"
    diff "$OUTPUT_DIR/${name}_run1.txt" "$OUTPUT_DIR/${name}_run2.txt" || true
    FAIL=1
  fi
done

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "🎉 전체 통과: 모든 검증 쿼리가 결정론적입니다."
else
  echo "⚠️  일부 쿼리가 실패했습니다. 위 로그를 확인하세요."
fi

exit $FAIL