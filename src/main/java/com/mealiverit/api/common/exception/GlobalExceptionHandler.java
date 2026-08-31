package com.mealiverit.api.common.exception;

import com.mealiverit.api.common.response.ErrorResponse;
import com.mealiverit.api.campaign.InvalidCampaignStateTransitionException;
import com.mealiverit.api.coupon.InvalidStateTransitionException;
import com.mealiverit.api.order.InvalidOrderStateTransitionException;
import org.hibernate.AssertionFailure;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    // entity 모듈은 api를 모르므로 도메인 예외를 직접 던진다. 여기서 HTTP 응답으로 번역한다.
    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStateTransition(InvalidStateTransitionException e) {
        ErrorCode errorCode = ErrorCode.COUPON_INVALID_STATE_TRANSITION;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    // entity 모듈은 api를 모르므로 도메인 예외를 직접 던진다. 여기서 HTTP 응답으로 번역한다.
    @ExceptionHandler(InvalidCampaignStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCampaignStateTransition(
            InvalidCampaignStateTransitionException e) {
        ErrorCode errorCode = ErrorCode.CAMPAIGN_INVALID_STATE_TRANSITION;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    // entity 모듈은 api를 모르므로 도메인 예외를 직접 던진다. 여기서 HTTP 응답으로 번역한다.
    @ExceptionHandler(InvalidOrderStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOrderStateTransition(InvalidOrderStateTransitionException e) {
        ErrorCode errorCode = ErrorCode.ORDER_INVALID_STATE_TRANSITION;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .findFirst()
                .orElse(ErrorCode.INVALID_REQUEST.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.name(), message));
    }

    // 필수 헤더 누락(X-User-Id, Idempotency-Key 등) — 기본값으로는 이 ControllerAdvice의
    // Exception 캐치올에 걸려 500이 나가버리므로 명시적으로 400 처리한다.
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.name(),
                        "필수 헤더가 없습니다: " + e.getHeaderName()));
    }

    // Campaign.@Version 낙관적 락 충돌 — 관리자 상태전이 API에서 동시에 두 요청이 같은 캠페인을 바꿀 때 발생.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException e) {
        ErrorCode errorCode = ErrorCode.CAMPAIGN_STATE_CONFLICT;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    // 상태전이 재시도 소진 시 새어나올 수 있는 두 예외 - 원인은 동일한데 IDENTITY 전략 특성상 예외 타입만 다르게 나옴.
    // 캐치올(500)로 원본 예외 메시지가 그대로 노출되는 것을 막기 위해 명시적으로 409 처리
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        ErrorCode errorCode = ErrorCode.COUPON_STATE_CONFLICT;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(AssertionFailure.class)
    public ResponseEntity<ErrorResponse> handleHibernateAssertionFailure(AssertionFailure e) {
        ErrorCode errorCode = ErrorCode.COUPON_STATE_CONFLICT;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("INTERNAL_SERVER_ERROR", e.getMessage()));
    }
}
