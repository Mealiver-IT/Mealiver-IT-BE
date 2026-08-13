# 정합성 검증 SQL 6종

설계 근거·결정론성 규칙(NOW() 금지, LEFT JOIN 필수 등)은
[`docs/planning/05_시스템설계.txt`](../../../../../../docs/planning/05_시스템설계.txt) 1절 참고. 여기 파일들은 그 설계를 그대로 옮긴
실행용 SQL이다 — 내용이 갈리면 05_시스템설계.txt가 우선이고 이 폴더를 그에 맞춰 고친다.

| 파일 | 검증 항목 | 결과 |
|---|---|---|
| `a_stock_overissue.sql` | 캠페인별 발급 수량이 재고를 초과하지 않았는가 | 0 rows |
| `b_duplicate_issue.sql` | 유저당 캠페인별 중복 발급이 없는가 | 0 rows |
| `c_counter_mismatch.sql` | 이력 테이블과 캠페인 카운터가 일치하는가 | 0 rows |
| `d_invalid_transition.sql` | 상태전이가 유효한가 (3개 쿼리) | 0 rows |
| `e_tier_violation.sql` | 회원 전용 쿠폰이 등급 미달 유저에게 발급된 적 없는가 | 0 rows |
| `f_tier_orders_mismatch.sql` | 계급이 orders 집계와 일치하는가 (`:월시작`/`:월종료` 치환 필요) | 0 rows |

## 지금은 수동 실행

`ConsistencyVerificationJob`(Spring Batch, Phase 2 선택 확장) 구현 전까지는 MySQL 클라이언트로 직접 실행한다.

```
mysql -h <host> -u <user> -p <database> < a_stock_overissue.sql
```

부하테스트 직후·더미데이터 적재 직후 6개 전부 실행해서 전부 0 rows인지 확인하는 것이 1차 진단.
