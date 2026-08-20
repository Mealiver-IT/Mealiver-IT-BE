package com.mealiverit.entity.campaign;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 재고 샤딩(2026-08-20)의 실제 재고 저장 단위. 캠페인 하나당 N개 row로 나눠서, 각 row가
// InnoDB 행 잠금을 독립적으로 받을 수 있게 한다 - campaign.remaining_stock은 더 이상 이 값의
// 원본이 아니라, 이 테이블 합계를 사후에 복사해두는 표시용 값이 된다(ShardedStockReservationStrategy 참고).
@Entity
@Table(name = "campaign_stock_shard")
public class CampaignStockShard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "shard_index", nullable = false)
    private int shardIndex;

    @Column(name = "remaining_stock", nullable = false)
    private int remainingStock;

    @Column(nullable = false)
    private int capacity;

    protected CampaignStockShard() {
        // JPA
    }

    public CampaignStockShard(Long campaignId, int shardIndex, int remainingStock, int capacity) {
        this.campaignId = campaignId;
        this.shardIndex = shardIndex;
        this.remainingStock = remainingStock;
        this.capacity = capacity;
    }

    public Long getId() {
        return id;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public int getShardIndex() {
        return shardIndex;
    }

    public int getRemainingStock() {
        return remainingStock;
    }

    public int getCapacity() {
        return capacity;
    }
}
