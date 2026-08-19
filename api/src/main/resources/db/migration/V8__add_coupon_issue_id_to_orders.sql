-- 주문과 쿠폰 발급 이력을 연결하기 위한 컬럼 추가 (멘토 피드백: 상태전이 중 장애 시 정합성 검증)
-- 지금까지 이 연결고리가 없어서, 주문 취소 후 쿠폰 상태전이가 실패해도 복구할 방법이 없었음
-- nullable인 이유: 쿠폰 미적용 주문도 있음
-- H2(테스트)는 MySQL과 달리 ALTER TABLE에서 ADD COLUMN/ADD CONSTRAINT/ADD INDEX를 한 문장으로
-- 묶는 걸 지원 안 해서 별도 문장으로 분리 (V6의 CREATE INDEX 분리 패턴과 동일)
ALTER TABLE orders ADD COLUMN coupon_issue_id BIGINT NULL;
ALTER TABLE orders ADD CONSTRAINT fk_orders_coupon_issue FOREIGN KEY (coupon_issue_id) REFERENCES coupon_issue (id);
CREATE INDEX idx_orders_coupon_issue ON orders(coupon_issue_id);