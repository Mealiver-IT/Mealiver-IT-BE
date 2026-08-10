package com.mealiverit.entity.user;

// 04_아키텍처.txt 6절: PRIVATE < PFC < CORPORAL < SERGEANT 순서.
// Campaign.isEligible()이 ordinal() 비교로 등급을 판정하므로 선언 순서를 바꾸면 안 된다.
public enum MembershipTier {
    PRIVATE,
    PFC,
    CORPORAL,
    SERGEANT
}
