package com.betterclouddrive.common.context;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RequestTraceContextTest {

    @Test
    void shouldGenerateW3cCompatibleTraceAndSpanIds() {
        assertThat(RequestTraceContext.generateTraceId()).matches("[0-9a-f]{32}");
        assertThat(RequestTraceContext.generateSpanId()).matches("[0-9a-f]{16}");
        assertThat(RequestTraceContext.generateRequestId()).matches("[0-9a-f]{16}");
    }

    @Test
    void clearShouldRemoveBoundContext() {
        RequestTraceContext.setRequestId("req");
        RequestTraceContext.setTraceId("trace");
        RequestTraceContext.setSpanId("span");

        RequestTraceContext.clear();

        assertThat(RequestTraceContext.getTraceId()).isNull();
        assertThat(RequestTraceContext.getSpanId()).isNull();
        assertThat(RequestTraceContext.getRequestId()).isNotEqualTo("req");
    }
}
