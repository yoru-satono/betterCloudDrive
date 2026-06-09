package com.betterclouddrive.common.exception;

import static org.assertj.core.api.Assertions.*;

import com.betterclouddrive.common.constant.ApiCode;
import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    void shouldCreateWithCodeAndMessage() {
        BusinessException ex = new BusinessException(500, "err");

        assertThat(ex.getCode()).isEqualTo(500);
        assertThat(ex.getMessage()).isEqualTo("err");
    }

    @Test
    void shouldCreateFromApiCode() {
        BusinessException ex = new BusinessException(ApiCode.FILE_NOT_FOUND);

        assertThat(ex.getCode()).isEqualTo(404001);
        assertThat(ex.getMessage()).isEqualTo("file not found");
    }

    @Test
    void shouldCreateFromApiCodeWithDetail() {
        BusinessException ex = new BusinessException(ApiCode.UNAUTHORIZED, "bad credentials");

        assertThat(ex.getCode()).isEqualTo(401);
        assertThat(ex.getMessage()).isEqualTo("bad credentials");
    }

    @Test
    void shouldBeRuntimeException() {
        BusinessException ex = new BusinessException(500, "x");

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
