# 정합성 검증 SQL 5종 (파일 7개)

설계 근거·결정론성 규칙(NOW() 금지, LEFT JOIN 필수 등)은
[`docs/planning/05_시스템설계.txt`](../../../../../../docs/planning/05_시스템설계.txt) 1절 참고. 여기 파일들은 그 설계를 그대로 옮긴
실행용 SQL이다 — 내용이 갈리면 05_시스템설계.txt가 우선이고 이 폴더를 그에 맞춰 고친다.

| 파일 | 검증 항목 | 결과 |
|---|---|---|
| `a_stock_overissue.sql` | 캠페인별 발급 수량이 재고를 초과하지 않았는가 | 0 rows |
| `b_counter_mismatch.sql` | 이력 테이블과 캠페인 카운터가 일치하는가 | 0 rows |
| `c1_missing_log.sql` / `c2_invalid_transition.sql` / `c3_broken_chain.sql` | 상태전이가 유효한가 (3개 쿼리) | 0 rows |
| `d_tier_violation.sql` | 회원 전용 쿠폰이 등급 미달 유저에게 발급된 적 없는가 | 0 rows |
| `e_tier_orders_mismatch.sql` | 계급이 orders 집계와 일치하는가 (`:월시작`/`:월종료` 치환 필요) | 0 rows |

> **[2026-08-14] 유저당 캠페인별 중복 발급 검증(구 `b_duplicate_issue.sql`)을 제거했다.**
> `coupon_issue`에 `uk_campaign_user UNIQUE (campaign_id, user_id)` 제약이 걸려 있어(`V1__create_core_tables.sql`)
> 중복 발급 행 자체가 INSERT 단계에서 거부된다. 제약을 우회하지 않는 한 이 쿼리가 검증할 위반 표본을 만들 수 없어
> 오염 데이터 삽입·탐지 시연 대상에서 제외한다. 나머지 파일들은 그 뒤를 한 칸씩 당겨 재명명했다(c→b, d1~d3→c1~c3, e→d, f→e).

## 지금은 수동 실행

`ConsistencyVerificationJob`(Spring Batch, Phase 2 선택 확장) 구현 전까지는 MySQL 클라이언트로 직접 실행한다.

```
mysql -h <host> -u <user> -p <database> < a_stock_overissue.sql
```

부하테스트 직후·더미데이터 적재 직후 7개 파일 전부 실행해서 전부 0 rows인지 확인하는 것이 1차 진단.
