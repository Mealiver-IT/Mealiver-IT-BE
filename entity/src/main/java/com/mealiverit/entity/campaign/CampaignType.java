package com.mealiverit.entity.campaign;

// boolean 플래그 대신 status/discountType과 동일한 VARCHAR+JPA enum 스타일로 통일
// 유형이 늘어나도(예: 이벤트성 프로모션) enum 값만 추가하면 대응 가능
public enum CampaignType {
    FCFS, // 선착순 이벤트
    MEMBERSHIP_BENEFIT // 계급별 혜택
}
