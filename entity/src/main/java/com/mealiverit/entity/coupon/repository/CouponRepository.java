package com.mealiverit.entity.coupon.repository;

import com.mealiverit.entity.coupon.entity.Coupon;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    // 캠페인:쿠폰 1:1 (04_아키텍처.txt 1절) — 발급 시 캠페인의 쿠폰 정책 조회용
    Optional<Coupon> findByCampaignId(Long campaignId);
}
