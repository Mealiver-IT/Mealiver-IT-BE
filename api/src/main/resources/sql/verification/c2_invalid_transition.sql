-- c-2) 허용되지 않은 전이가 로그에 기록된 적 있는지
-- 주의: from_status는 nullable이라 NULL 비교 시 SQL 3값 논리 함정을 피하려 COALESCE 사용.
-- ISSUED는 최초상태라 to_status로 등장하면 안 되므로 '|ISSUED'는 허용 목록에 없다
-- (coupon_state_log를 쓰는 곳은 markUsed/markCanceled/markReturnedToIssued/만료배치뿐이고
-- 전부 실제 from_status를 넘기지 NULL을 넘기지 않는다).
-- USED|ISSUED: 주문취소 시 본인 재사용 복귀 (2026-08-13 팀 결정, FR-CPS-002/004, 05_시스템설계.txt (c) 참고)
SELECT * FROM coupon_state_log
WHERE CONCAT(COALESCE(from_status, '\0'), '|', to_status) NOT IN (
    'ISSUED|USED', 'ISSUED|CANCELED', 'ISSUED|EXPIRED', 'USED|CANCELED', 'USED|ISSUED'
)
ORDER BY id;