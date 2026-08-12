package com.mealiverit.entity.coupon.repository;

import com.mealiverit.entity.coupon.entity.Coupon;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    // 캠페인:쿠폰 1:1 (04_아키텍처.txt 1절) — 발급 시 캠페인의 쿠폰 정책 조회용
    Optional<Coupon> findByCampaignId(Long campaignId);

    // 캠페인 목록 조회용 — N+1 방지를 위해 campaignId 목록으로 한 번에 조회 (08_개발표준.txt 2절)
    List<Coupon> findByCampaignIdIn(Collection<Long> campaignIds);
}
