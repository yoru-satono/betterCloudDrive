package com.betterclouddrive.common.exception;

import lombok.Getter;

@Getter
public class InspectionException extends RuntimeException {
    private final int status;
    private final String messageKey;
    public InspectionException(int status, String key) { super(key); this.status = status; this.messageKey = key; }
}
