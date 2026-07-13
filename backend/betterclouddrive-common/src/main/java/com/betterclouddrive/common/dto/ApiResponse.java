package com.betterclouddrive.common.dto;

import com.betterclouddrive.common.context.RequestTraceContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private int code;
    private String messageKey;
    @Builder.Default
    private Map<String, Object> messageParams = Map.of();
    private T data;
    private long timestamp;
    private String requestId;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .messageKey(com.betterclouddrive.common.message.MessageKeys.SUCCESS)
                .messageParams(Map.of())
                .data(data)
                .timestamp(System.currentTimeMillis())
                .requestId(RequestTraceContext.getRequestId())
                .build();
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> error(int code, String messageKey) {
        return error(code, messageKey, Map.of(), null);
    }

    public static <T> ApiResponse<T> error(int code, String messageKey, Map<String, Object> messageParams) {
        return error(code, messageKey, messageParams, null);
    }

    public static <T> ApiResponse<T> error(int code, String messageKey, Map<String, Object> messageParams, T data) {
        return ApiResponse.<T>builder()
                .code(code)
                .messageKey(messageKey)
                .messageParams(messageParams == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(messageParams)))
                .data(data)
                .timestamp(System.currentTimeMillis())
                .requestId(RequestTraceContext.getRequestId())
                .build();
    }

}
