# Phase 11 — 실행 경위 정리

실제 결과 숫자는 시나리오별 README에 있습니다 — **[`race/README.md`](race/README.md)**,
**[`retry_mix/README.md`](retry_mix/README.md)**. 이 문서는 "왜 race/retry_mix를 항상
따로 돌리게 됐는지"의 경위만 남겨둡니다 (중복 방지를 위해 숫자 표는 이 문서에서 뺐습니다).

## 경위

1. 처음엔 스텁 서버로 스크립트 문법만 확인.
2. 실제 서버로 넘어가서 race+retry_mix를 **같이** 돌려봄 (원래 명세가 "혼합 시나리오"라는
   표현을 썼어서). 20,000 VU 규모에서 실패율이 36.4%까지 나왔는데, race와 retry가 같은
   재고를 나눠 쓰고 실패의 상당수가 클라이언트(로드 생성기 PC) 문제라 "race 자체는
   괜찮은데 retry가 문제인지, 그 반대인지"가 안 갈라짐.
3. 그래서 **race만 따로, retry_mix만 따로** 재실행 → 각각 단독으로는 완벽하게 동작함을
   확인. 즉 3단계에서 본 실패는 race나 retry_mix 자체의 결함이 아니라, **두 시나리오를
   합쳐서 25,000 VU를 한 컴퓨터에서 동시에 낼 때 생기는 로컬 TCP 임시 포트 고갈**
   때문이었음이 명확해짐.
4. 폴더 구조도 `results/stub/`+`results/real/`(대상 서버 기준) → `race/`+`retry_mix/`+
   `mixed/`(시나리오 기준, phase3 `campaign_299`/`300` 폴더 구조 참고) → **"시나리오는
   2개인데 왜 mixed까지 3개냐"는 피드백으로 `mixed/`(race+retry_mix 동시 실행 회차)의
   원본 파일은 전부 삭제**하고 `race/`, `retry_mix/` 둘만 남기는 순서로 정리됨.

## 핵심 결론

- **초과발급 0건, 멱등성 버그(`retry_duplicate_issue_rate`) 0건 — 지금까지 남아있는
  race/retry_mix 단독 실행 전부.** 서버 쪽 동시성 제어(재고 락, idempotency 가드)는
  이번 시나리오 범위에서 정상 동작.
- **대량 동시 연결 실패는 서버가 아니라 로드 생성기 PC의 로컬 포트 고갈** 때문이었음
  (`connectex: Only one usage of each socket address...` 에러) — phase3에서 겪은 것과
  동일한 종류의 클라이언트 자원 한계.
- **retry_mix가 phase3의 `campaign_300`에서 찾은 "동시 중복요청 락 증폭 버그"를 재현
  안 하는 이유**: phase11의 retry_mix는 재시도 사이 1초(`RETRY_BACKOFF`) 쉬고 보내서,
  두 번째 요청이 도착할 땐 첫 번째가 이미 처리 완료된 뒤라 애초에 레이스 컨디션이 생기지
  않음. phase3는 4개 요청을 거의 동시에 쏴서 그 레이스를 일부러 만들었던 것 — 서로 다른
  스트레스 조건을 검증한 것이라 두 결과가 모순되는 게 아님.
