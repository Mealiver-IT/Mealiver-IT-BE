package com.mealiverit.entity.coupon;

// 상태전이 감사 로그(CouponStateLog)에 왜 바뀌었는지를 남기기 위한 사유 코드
// 각 값은 CouponStateTransitionOperations/CouponExpirationBatchJob의 호출 지점과 1:1 대응
public enum CouponStateChangeReason {
    ORDER_PAYMENT,  // 결제 완료로 인한 사용 처리 (markUsed)
    ORDER_CANCEL,   // 주문 취소로 인한 재사용 복귀 (markReturnedToIssued)
    ADMIN_REVOKE,   // 관리자 강제 회수 (markCanceled)
    SYSTEM_EXPIRY,  // 만료 배치 (CouponExpirationBatchJob)
    UNKNOWN         // 이 컬럼 추가 이전에 생성된 레코드용 - 새 코드에서는 절대 안 씀
}
