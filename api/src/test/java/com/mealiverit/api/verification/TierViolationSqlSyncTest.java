package com.mealiverit.api.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.mealiverit.entity.user.MembershipTier;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

// d_tier_violation.sql:16 관련 (리뷰 코멘트 12번)
//
// 문제: d_tier_violation.sql이 등급 순서 비교를 FIELD(tier, 'PRIVATE','PFC','CORPORAL','SERGEANT')로
// 처리한다. 이 리터럴 나열은 MembershipTier enum의 이름/순서를 SQL 쪽에서 독립적으로 하드코딩한
// 것이라, enum에 값이 추가되거나 이름이 바뀌어도 SQL은 컴파일 에러 없이 그대로 실행된다.
// FIELD()는 목록에서 못 찾은 값에 대해 예외 없이 0을 반환하므로, 동기화가 깨지면 검증 쿼리가
// 에러 없이 "조용히" 잘못된 결과(과탐 또는 누락)를 낼 수 있다.
//
// 이 테스트는 그 동기화를 자동으로 보장하진 않지만, enum이 바뀌었는데 SQL을 안 고치면
// CI에서 이 테스트가 실패하도록 만들어 "조용한 무력화"를 "시끄러운 실패"로 바꾼다.
// 이 테스트가 실패하면 -> sql/verification/d_tier_violation.sql의 FIELD() 리스트도 같이 고쳐야 한다.
class TierViolationSqlSyncTest {

    @Test
    void tierViolationSql이_MembershipTier_enum과_동기화되어야_한다() {
        // FIELD() 안의 리터럴과 enum 값+순서가 정확히 일치하는지 확인
        String expectedOrder = Arrays.stream(MembershipTier.values())
                .map(Enum::name)
                .collect(Collectors.joining("','", "'", "'"));

        assertThat(expectedOrder).isEqualTo("'PRIVATE','PFC','CORPORAL','SERGEANT'");
        // 이 assert가 실패하면 -> d_tier_violation.sql의 FIELD() 리스트도 같이 고쳐야 한다는 신호
    }
}