DROP INDEX idx_orders_tier_calc ON orders;

CREATE INDEX idx_orders_user_status_completed_paid
  ON orders(user_id, status, completed_at, paid_amount);