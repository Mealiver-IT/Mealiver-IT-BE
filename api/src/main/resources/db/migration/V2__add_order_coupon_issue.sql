ALTER TABLE orders
    ADD COLUMN coupon_issue_id BIGINT NULL;

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_coupon_issue FOREIGN KEY (coupon_issue_id) REFERENCES coupon_issue (id);