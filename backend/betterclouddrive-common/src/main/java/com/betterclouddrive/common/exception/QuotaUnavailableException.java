package com.betterclouddrive.common.exception;

/** Stable protocol error; never includes Redis values or connection diagnostics. */
public class QuotaUnavailableException extends RuntimeException {
    public QuotaUnavailableException() { super("quota_accounting_not_ready"); }
}
