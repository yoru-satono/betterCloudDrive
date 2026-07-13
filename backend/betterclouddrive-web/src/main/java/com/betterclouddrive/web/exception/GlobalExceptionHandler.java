package com.betterclouddrive.web.exception;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;
import java.util.Map;
import com.betterclouddrive.common.message.MessageKeys;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessageKey(), e.getMessageParams());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String fields = e.getBindingResult().getFieldErrors().stream().map(err -> err.getField()).distinct().collect(Collectors.joining(","));
        return ApiResponse.error(ApiCode.BAD_REQUEST.getCode(), MessageKeys.VALIDATION_FAILED, Map.of("fields", fields));
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());
        return ApiResponse.error(ApiCode.FORBIDDEN.getCode(), ApiCode.FORBIDDEN.getMessageKey());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ApiResponse.error(ApiCode.METHOD_NOT_ALLOWED.getCode(), MessageKeys.METHOD_NOT_ALLOWED,
                Map.of("method", e.getMethod()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        return ApiResponse.error(ApiCode.BAD_REQUEST.getCode(), MessageKeys.MALFORMED_BODY);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException e) {
        String fields = e.getConstraintViolations().stream().map(v -> v.getPropertyPath().toString()).distinct().collect(Collectors.joining(","));
        return ApiResponse.error(ApiCode.BAD_REQUEST.getCode(), MessageKeys.VALIDATION_FAILED, Map.of("fields", fields));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return ApiResponse.error(ApiCode.BAD_REQUEST.getCode(), MessageKeys.MISSING_PARAMETER,
                Map.of("name", e.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ApiResponse.error(ApiCode.BAD_REQUEST.getCode(), MessageKeys.INVALID_PARAMETER, Map.of("name", e.getName()));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnknown(Exception e) {
        log.error("Unhandled exception", e);
        return ApiResponse.error(ApiCode.INTERNAL_ERROR.getCode(), MessageKeys.INTERNAL_ERROR);
    }
}
