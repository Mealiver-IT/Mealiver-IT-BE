-- d-2) 허용되지 않은 전이가 로그에 기록된 적 있는지
SELECT * FROM coupon_state_log
WHERE CONCAT(COALESCE(from_status, ''), '|', to_status) NOT IN (
    '|ISSUED',
    'ISSUED|USED', 'ISSUED|CANCELED', 'ISSUED|EXPIRED', 'USED|CANCELED'
)
ORDER BY id;