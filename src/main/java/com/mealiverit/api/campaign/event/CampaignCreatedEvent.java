package com.mealiverit.api.campaign.event;

// 캠페인 생성(POST /api/campaigns) 성공 시 발행 - 재고 샤드를 생성 시점에 바로 만들기 위한 용도
// (CampaignShardInitListener 참고). 커밋 전에 샤드부터 만들면 이후 create()가 실패해서 롤백될 때
// campaign 행 없는 campaign_stock_shard 고아 행이 남으므로, 반드시 AFTER_COMMIT에서만 처리해야 한다.
public record CampaignCreatedEvent(Long campaignId) {
}
