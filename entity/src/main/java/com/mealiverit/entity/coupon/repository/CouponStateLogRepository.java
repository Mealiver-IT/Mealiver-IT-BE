package com.mealiverit.entity.coupon.repository;

import com.mealiverit.entity.coupon.entity.CouponStateLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CouponStateLogRepository extends JpaRepository<CouponStateLog, Long> {

    // 상태전이 요청 멱등성 체크 (04_아키텍처.txt 5.2절)
    boolean existsByRequestId(String requestId);

    // 동시성 테스트에서 로그 체인(from -> to 연속성) 검증용
    List<CouponStateLog> findByCouponIssueIdOrderById(Long couponIssueId);
}
