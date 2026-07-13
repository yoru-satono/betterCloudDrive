package com.betterclouddrive.common.exception;

import com.betterclouddrive.common.constant.ApiCode;
import lombok.Getter;

import java.util.Map;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final String messageKey;
    private final Map<String, Object> messageParams;

    public BusinessException(int code, String messageKey) {
        this(code, messageKey, Map.of(), null);
    }

    public BusinessException(int code, String messageKey, Map<String, Object> messageParams, String diagnosticMessage) {
        super(diagnosticMessage == null ? messageKey : diagnosticMessage);
        this.code = code;
        this.messageKey = messageKey;
        this.messageParams = messageParams == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(messageParams));
    }

    public BusinessException(ApiCode apiCode) {
        this(apiCode.getCode(), apiCode.getMessageKey(), Map.of(), apiCode.getMessage());
    }

    public BusinessException(ApiCode apiCode, String detail) {
        this(apiCode.getCode(), apiCode.getMessageKey(), Map.of(), detail != null ? detail : apiCode.getMessage());
    }

    public BusinessException(ApiCode apiCode, String messageKey, Map<String, Object> messageParams) {
        this(apiCode.getCode(), messageKey, messageParams, apiCode.getMessage());
    }
}
