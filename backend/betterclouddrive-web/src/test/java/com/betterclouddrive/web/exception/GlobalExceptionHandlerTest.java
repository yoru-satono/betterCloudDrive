package com.betterclouddrive.web.exception;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import static org.assertj.core.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void shouldHandleBusinessException() {
        BusinessException ex = new BusinessException(ApiCode.FILE_NOT_FOUND);
        ApiResponse<?> response = handler.handleBusinessException(ex);
        assertThat(response.getCode()).isEqualTo(404001);
        assertThat(response.getMessage()).isEqualTo("file not found");
    }

    @Test
    void shouldHandleValidationException() {
        // Create a dummy MethodArgumentNotValidException
        var bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("request", "username", "must not be blank"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ApiResponse<?> response = handler.handleValidation(ex);
        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).contains("username");
        assertThat(response.getMessage()).contains("must not be blank");
    }

    @Test
    void shouldHandleMultipleValidationErrors() {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("request", "username", "size must be between 3 and 64"));
        bindingResult.addError(new FieldError("request", "password", "must not be blank"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ApiResponse<?> response = handler.handleValidation(ex);
        assertThat(response.getMessage()).contains("username").contains("password");
    }

    @Test
    void shouldHandleUnknownException() {
        Exception ex = new RuntimeException("unexpected error");
        ApiResponse<?> response = handler.handleUnknown(ex);
        assertThat(response.getCode()).isEqualTo(500);
        assertThat(response.getMessage()).isEqualTo("internal server error");
    }
}
