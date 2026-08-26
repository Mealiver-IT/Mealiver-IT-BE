package com.mealiverit.api.verification.report;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class JobCheckTypeResolver {
	
    private static final Map<String, Set<CheckType>> JOB_CHECK_TYPES = Map.of(

            "DailyConsistencyVerificationJob", EnumSet.of(
                    CheckType.STOCK_OVERISSUE,
                    CheckType.COUNTER_MISMATCH,
                    CheckType.STATE_MISSING_LOG,
                    CheckType.STATE_INVALID_TRANSITION,
                    CheckType.STATE_BROKEN_CHAIN,
                    CheckType.TIER_ELIGIBILITY_VIOLATION
            ),

            "TierOrdersMismatchJob", EnumSet.of(
                    CheckType.TIER_CONSISTENCY_MISMATCH
            )
    );

    public Set<CheckType> resolve(String jobName) {
        return JOB_CHECK_TYPES.getOrDefault(
                jobName,
                EnumSet.noneOf(CheckType.class)
        );
    }
}