package com.betterclouddrive.common.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ApiCode {

    // ==================== Success ====================
    SUCCESS(200, "success"),

    // ==================== 4xx Client Errors ====================
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    TOKEN_EXPIRED(401001, "token expired"),
    TOKEN_BLACKLISTED(401002, "token revoked"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "resource not found"),
    FILE_NOT_FOUND(404001, "file not found"),
    METHOD_NOT_ALLOWED(405, "method not allowed"),
    CONFLICT(409, "resource conflict"),
    FILE_NAME_CONFLICT(409001, "file name already exists in this folder"),
    RATE_LIMITED(429, "too many requests"),

    // ==================== Business Errors (419) ====================
    STORAGE_QUOTA_EXCEEDED(419001, "storage quota exceeded"),
    INVALID_SHARE_CODE(419002, "invalid share code"),
    SHARE_PASSWORD_REQUIRED(419003, "password required"),
    SHARE_EXPIRED(419004, "share link has expired"),
    SHARE_VISIT_LIMIT(419005, "visit limit reached"),
    CHUNK_UPLOAD_INVALID(419006, "invalid chunk upload state"),
    CHUNK_MD5_MISMATCH(419007, "chunk MD5 does not match"),
    FILE_TOO_LARGE(419008, "file exceeds maximum allowed size"),
    VERSION_LIMIT_REACHED(419009, "maximum version limit reached"),
    INSTANT_UPLOAD_NOT_FOUND(419010, "no matching file found for instant upload"),
    EMAIL_VERIFICATION_FAILED(419011, "email verification failed"),
    EMAIL_CODE_EXPIRED(419012, "verification code expired"),
    EMAIL_CODE_MISMATCH(419013, "verification code mismatch"),
    PASSWORD_RESET_FAILED(419014, "password reset failed"),
    FOLDER_DOWNLOAD_LIMIT_EXCEEDED(419015, "folder download limit exceeded"),

    // ==================== 5xx Server Errors ====================
    INTERNAL_ERROR(500, "internal server error"),
    STORAGE_ERROR(500001, "storage service error"),
    UPLOAD_FAILED(500002, "upload failed"),
    DOWNLOAD_FAILED(500003, "download failed");

    private final int code;
    private final String message;
}
