package com.mealiverit.api.common.response;

public record ApiResponse<T>(boolean success, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data);
    }

    public static ApiResponse<Void> empty() {
        return new ApiResponse<>(true, null);
    }
}
