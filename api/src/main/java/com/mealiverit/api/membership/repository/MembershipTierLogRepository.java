package com.mealiverit.api.membership.repository;

import com.mealiverit.api.membership.entity.MembershipTierLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipTierLogRepository extends JpaRepository<MembershipTierLog, Long> {
}
