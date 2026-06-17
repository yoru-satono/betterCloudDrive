package com.betterclouddrive.common.dto;

import static org.assertj.core.api.Assertions.*;

import com.betterclouddrive.common.context.RequestTraceContext;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void shouldCreateSuccessResponse() {
        ApiResponse<Object> response = ApiResponse.success();

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getMessage()).isEqualTo("success");
        assertThat(response.getTimestamp()).isNotNull();
        assertThat(response.getRequestId()).isNotNull();
        assertThat(response.getData()).isNull();
    }

    @Test
    void shouldCreateSuccessWithData() {
        ApiResponse<String> response = ApiResponse.success("test");

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getMessage()).isEqualTo("success");
        assertThat(response.getData()).isEqualTo("test");
    }

    @Test
    void shouldCreateErrorResponse() {
        ApiResponse<Object> response = ApiResponse.error(500, "error msg");

        assertThat(response.getCode()).isEqualTo(500);
        assertThat(response.getMessage()).isEqualTo("error msg");
        assertThat(response.getData()).isNull();
        assertThat(response.getRequestId()).isNotNull();
    }

    @Test
    void shouldCreateErrorWithData() {
        String details = "validation failed";
        ApiResponse<String> response = ApiResponse.error(400, "bad", details);

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).isEqualTo("bad");
        assertThat(response.getData()).isEqualTo(details);
    }

    @Test
    void shouldHaveUniqueRequestIdsPerCall() {
        ApiResponse<Object> first = ApiResponse.success();
        ApiResponse<Object> second = ApiResponse.success();

        assertThat(first.getRequestId()).isNotNull();
        assertThat(second.getRequestId()).isNotNull();
        assertThat(first.getRequestId()).isNotEqualTo(second.getRequestId());
    }

    @Test
    void shouldUseBoundRequestIdWhenPresent() {
        RequestTraceContext.setRequestId("request-1");
        try {
            ApiResponse<Object> response = ApiResponse.success();
            assertThat(response.getRequestId()).isEqualTo("request-1");
        } finally {
            RequestTraceContext.clear();
        }
    }
}
