"""users_20000.json 재생성 스크립트.

api/users_20000.json은 .gitignore에 걸려있어 커밋되지 않는다(1.6MB 생성 파일이라
git에 넣지 않기로 함). 대신 이 스크립트를 커밋해서, 필요한 사람이 로컬에서
바로 재생성할 수 있게 한다. DB 접속 불필요 — 표준 라이브러리만 사용한다.

id-loginId 매핑 가정: 100만 유저 더미데이터 시더가 users 테이블에 가장 먼저
"user" + i (i=0..999999) 형태로 순서대로 INSERT하므로, AUTO_INCREMENT PK는
loginId="userN" -> id=N+1 이 된다. 2026-08-14 로컬/리모트 DB 둘 다 이 매핑이
실제로 일치하는 것을 확인했다(SELECT login_id, id FROM users WHERE login_id
IN ('user0','user1','user2','user19999')). 만약 이후 users 테이블에 다른
row가 먼저 들어가는 식으로 시딩 순서가 바뀌면 이 가정이 깨지니, 재생성 전에
위 쿼리로 한 번 더 확인할 것을 권장한다.

출력 형식 — k6 스크립트에서 쓸 값:
  id           -> X-User-Id 헤더에 그대로 사용 (숫자, Long 파싱 필수)
  loginId      -> 참고/로깅용, API에는 안 씀
  idempotencyKey -> Idempotency-Key 헤더에 그대로 사용

사용법:
  python generate_users_20000.py                # api/users_20000.json에 출력
  python generate_users_20000.py --count 5000    # 개수 조절 (기본 20000)
  python generate_users_20000.py --out out.json  # 출력 경로 지정
"""

import argparse
import json
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=20000, help="생성할 유저 수 (기본 20000)")
    parser.add_argument(
        "--out",
        type=Path,
        default=Path(__file__).resolve().parents[4] / "users_20000.json",
        help="출력 경로 (기본: api/users_20000.json)",
    )
    args = parser.parse_args()

    users = generate(args.count)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(users, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{len(users)}명 -> {args.out}")


if __name__ == "__main__":
    main()
