"""users_20000.json 재생성 스크립트.

api/users_20000.json은 .gitignore에 걸려있어 커밋되지 않는다(1.6MB 생성 파일이라
git에 넣지 않기로 함). 대신 이 스크립트를 커밋해서, 필요한 사람이 로컬에서
바로 재생성할 수 있게 한다.

id-loginId 매핑 가정: 100만 유저 더미데이터 시더가 users 테이블에 가장 먼저
"user" + i (i=0..999999) 형태로 순서대로 INSERT하므로, AUTO_INCREMENT PK는
loginId="userN" -> id=N+1 이 된다. 이건 "users 테이블에 아무도 먼저 다른 row를
안 넣었다"는 조건에서만 성립하는 가정이라 깨질 수 있다 — 누가 테스트용 유저를
수동으로 먼저 넣었거나, 시더를 TRUNCATE 없이 재실행했거나, 로컬/리모트가
서로 다르게 리셋됐으면 조용히 틀린 id가 나온다(에러 없이 엉뚱한 유저로 잘못
채워짐). 그래서 기본적으로 파일을 쓰기 전에 실제 DB에 대고 이 가정을
검증하고, 안 맞으면 파일을 쓰지 않고 에러로 멈춘다.

출력 형식 — k6 스크립트에서 쓸 값:
  id           -> X-User-Id 헤더에 그대로 사용 (숫자, Long 파싱 필수)
  loginId      -> 참고/로깅용, API에는 안 씀
  idempotencyKey -> Idempotency-Key 헤더에 그대로 사용

사용법:
  python generate_users_20000.py                         # 로컬 DB(docker exec mealiver-mysql)로 검증 후 생성
  python generate_users_20000.py --count 5000             # 개수 조절 (기본 20000)
  python generate_users_20000.py --out out.json           # 출력 경로 지정
  python generate_users_20000.py --skip-verify            # DB 검증 생략 (docker 없을 때만 쓸 것)
  python generate_users_20000.py --host 100.125.247.64 --port 3306 \
      --user mealiver --password mealiver1234 --database mealiver
                                                            # 리모트 DB로 검증
"""

import argparse
import json
import subprocess
import sys
import uuid
from pathlib import Path


def generate(count: int) -> list[dict]:
    return [
        {
            "id": i + 1,
            "loginId": f"user{i}",
            "idempotencyKey": str(uuid.uuid4()),
        }
        for i in range(count)
    ]


def query_id(args: argparse.Namespace, login_id: str) -> int | None:
    """login_id로 실제 users.id를 조회한다. 조회 실패 시 None."""
    sql = f"SELECT id FROM users WHERE login_id='{login_id}';"

    if args.host:
        cmd = [
            "docker", "run", "--rm", "mysql:8", "mysql",
            "-h", args.host, "-P", str(args.port),
            "-u", args.user, f"-p{args.password}", args.database,
            "-e", sql,
        ]
    else:
        cmd = [
            "docker", "exec", "mealiver-mysql", "mysql",
            "-uroot", "-proot", "mealiverit",
            "-e", sql,
        ]

    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    except FileNotFoundError:
        print("docker 명령을 찾을 수 없습니다. Docker Desktop이 실행 중인지 확인하거나 --skip-verify로 건너뛰세요.", file=sys.stderr)
        return None
    except subprocess.TimeoutExpired:
        print("DB 조회가 30초 넘게 걸려 중단했습니다.", file=sys.stderr)
        return None

    if result.returncode != 0:
        print(f"DB 조회 실패:\n{result.stderr.strip()}", file=sys.stderr)
        return None

    lines = [line for line in result.stdout.strip().splitlines() if line.strip()]
    # mysql -e 출력은 헤더 1줄 + 값 1줄. 값이 없으면(해당 login_id 없음) 헤더만 남는다.
    if len(lines) < 2:
        return None
    try:
        return int(lines[-1].strip())
    except ValueError:
        return None


def verify(args: argparse.Namespace, count: int) -> bool:
    checks = [("user0", 1), (f"user{count - 1}", count)]
    for login_id, expected_id in checks:
        actual_id = query_id(args, login_id)
        if actual_id is None:
            print(f"검증 실패: {login_id}의 id를 DB에서 못 찾았습니다.", file=sys.stderr)
            return False
        if actual_id != expected_id:
            print(
                f"검증 실패: {login_id}의 실제 id={actual_id}, 예상 id={expected_id}. "
                "id-loginId 매핑 가정이 깨진 상태입니다 — users 테이블 시딩 순서를 확인하세요.",
                file=sys.stderr,
            )
            return False
        print(f"검증 통과: {login_id} -> id={actual_id}")
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--count", type=int, default=20000, help="생성할 유저 수 (기본 20000)")
    parser.add_argument(
        "--out",
        type=Path,
        default=Path(__file__).resolve().parents[4] / "users_20000.json",
        help="출력 경로 (기본: api/users_20000.json)",
    )
    parser.add_argument("--skip-verify", action="store_true", help="DB 검증 생략하고 바로 생성 (docker 없을 때만 사용 권장)")
    parser.add_argument("--host", default=None, help="검증용 DB 호스트 (생략 시 로컬 docker exec mealiver-mysql 사용)")
    parser.add_argument("--port", type=int, default=3306, help="검증용 DB 포트 (기본 3306, --host와 함께 사용)")
    parser.add_argument("--user", default="mealiver", help="검증용 DB 유저 (--host와 함께 사용)")
    parser.add_argument("--password", default="", help="검증용 DB 비밀번호 (--host와 함께 사용)")
    parser.add_argument("--database", default="mealiver", help="검증용 DB 이름 (--host와 함께 사용)")
    args = parser.parse_args()

    if args.skip_verify:
        print("⚠️  DB 검증을 생략합니다 — id-loginId 매핑이 실제와 다를 수 있습니다.")
    elif not verify(args, args.count):
        print("파일을 쓰지 않고 중단합니다. 필요하면 --skip-verify로 강제 생성할 수 있습니다.", file=sys.stderr)
        sys.exit(1)

    users = generate(args.count)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(users, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{len(users)}명 -> {args.out}")


if __name__ == "__main__":
    main()
