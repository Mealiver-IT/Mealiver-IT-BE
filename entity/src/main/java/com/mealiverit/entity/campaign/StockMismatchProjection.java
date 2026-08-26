package com.mealiverit.entity.campaign;

// CampaignRepository.findStockMismatches()의 네이티브 쿼리 결과 projection.
// total_stock = shardRemaining + issuedCount 불변식이 깨진 캠페인만 담긴다.
public interface StockMismatchProjection {

    Long getCampaignId();

    Integer getTotalStock();

    Integer getShardRemaining();

    Integer getIssuedCount();
}
