-- idx_orders_tier_calc(user_id, status, completed_at)는 orders.user_id의 FK(fk_orders_user)를
-- 지지하는 인덱스라, 새 인덱스를 먼저 안 만들고 바로 DROP하면 MySQL이 거부한다
-- (Error 1553: Cannot drop index ... needed in a foreign key constraint). 새 인덱스도
-- user_id로 시작해 FK를 대신 지지할 수 있으므로, CREATE를 먼저 하고 DROP을 나중에 한다.
CREATE INDEX idx_orders_user_status_completed_paid
  ON orders(user_id, status, completed_at, paid_amount);

DROP INDEX idx_orders_tier_calc ON orders;