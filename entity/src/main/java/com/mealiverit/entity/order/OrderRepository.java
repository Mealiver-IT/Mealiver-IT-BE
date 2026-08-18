package com.mealiverit.entity.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // 유저별 주문 목록 조회 메소드
    List<Order> findByUserIdOrderByOrderedAtDesc(Long userId);
}
