package com.mealiverit.api.common.exception;

import com.mealiverit.api.common.response.ErrorResponse;
import com.mealiverit.entity.campaign.InvalidCampaignStateTransitionException;
import com.mealiverit.entity.coupon.InvalidStateTransitionException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .findFirst()
                .orElse(ErrorCode.INVALID_REQUEST.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST.name(), message));
    }

    // Campaign.@Version 낙관적 락 충돌 — 관리자 상태전이 API에서 동시에 두 요청이 같은 캠페인을 바꿀 때 발생.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException e) {
        ErrorCode errorCode = ErrorCode.CAMPAIGN_STATE_CONFLICT;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("INTERNAL_SERVER_ERROR", e.getMessage()));
    }
}
