package com.mealiverit.api.coupon.service;

// 03_버전사다리_실험설계.txt 3절 — 동시성 전략(V1~V4)마다 이 인터페이스만 다르게 구현한다.
// CouponIssueService의 나머지 로직(멱등성, eligibility, INSERT, 트랜잭션 경계 등)은 전 버전 공통.
public interface StockReservationStrategy {

    // 재고 확보 시도. 성공하면 true, 품절이면 false.
    boolean reserve(Long campaignId);

    // reserve() 성공 후 후속 단계(INSERT 등)가 실패했을 때 원복.
    void rollback(Long campaignId);
}
