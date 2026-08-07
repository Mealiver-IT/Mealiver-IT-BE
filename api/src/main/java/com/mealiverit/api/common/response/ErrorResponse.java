package com.mealiverit.api.common.response;

import com.mealiverit.api.common.exception.ErrorCode;

public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage());
    }
}
