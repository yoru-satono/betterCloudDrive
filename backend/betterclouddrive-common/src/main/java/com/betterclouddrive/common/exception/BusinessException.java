package com.betterclouddrive.common.exception;

import com.betterclouddrive.common.constant.ApiCode;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ApiCode apiCode) {
        super(apiCode.getMessage());
        this.code = apiCode.getCode();
    }

    public BusinessException(ApiCode apiCode, String detail) {
        super(detail != null ? detail : apiCode.getMessage());
        this.code = apiCode.getCode();
    }
}
