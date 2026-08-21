-- 캠페인 재고를 여러 row(샤드)로 쪼개 hot row 경합을 분산한다(2026-08-20 coupon_mixed_5k_x4.js
-- 부하테스트 - 락 보유시간을 줄여도(V9 이전 커밋) 20,000건이 결국 campaign row 하나로 몰리는
-- 처리량 한계는 그대로였음). 기존 캠페인 백필은 별도 마이그레이션 없이 애플리케이션에서
-- 지연 생성(첫 예약 요청 시점에 campaign.remaining_stock 기준으로 샤드 생성)으로 처리한다.
CREATE TABLE campaign_stock_shard (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    shard_index INT NOT NULL,
    remaining_stock INT NOT NULL,
    capacity INT NOT NULL,
    UNIQUE KEY uk_campaign_shard (campaign_id, shard_index),
    CONSTRAINT fk_stock_shard_campaign FOREIGN KEY (campaign_id) REFERENCES campaign(id)
);
