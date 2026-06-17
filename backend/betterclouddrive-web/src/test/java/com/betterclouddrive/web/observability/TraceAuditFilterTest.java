package com.betterclouddrive.web.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.context.RequestTraceContext;
import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.service.OperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

@ExtendWith(MockitoExtension.class)
class TraceAuditFilterTest {

    @Mock
    private OperationLogService operationLogService;

    @Test
    void shouldBindRequestIdAndWriteAudit() throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        TraceAuditFilter filter = new TraceAuditFilter(
                properties,
                new AuditSanitizer(properties),
                operationLogService);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/files");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.addHeader(RequestTraceContext.HEADER_REQUEST_ID, "req-123");
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        request.setContent("{\"password\":\"secret\"}".getBytes());
        request.setAttribute(RequestTraceContext.ATTRIBUTE_USER_ID, 7L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            req.getInputStream().readAllBytes();
            res.getWriter().write(objectMapper.writeValueAsString(ApiResponse.success("ok")));
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestTraceContext.HEADER_REQUEST_ID)).isEqualTo("req-123");
        assertThat(response.getHeader(RequestTraceContext.HEADER_TRACE_ID))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(response.getContentAsString()).contains("\"requestId\":\"req-123\"");

        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(operationLogService).logRequestAudit(
                eq(7L),
                eq("OTHER"),
                eq("FILE"),
                isNull(),
                detailCaptor.capture(),
                any(),
                isNull(),
                eq(1),
                anyInt(),
                eq("req-123"),
                eq("4bf92f3577b34da6a3ce929d0e0e4736"),
                eq(200),
                isNull());

        assertThat(detailCaptor.getValue())
                .contains("\"request_id\":\"req-123\"")
                .contains("\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\"")
                .contains("\"status\":200")
                .doesNotContain("secret");
        assertThat(RequestTraceContext.getTraceId()).isNull();
    }

    @Test
    void shouldSkipBodyCaptureForUploadPaths() throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        TraceAuditFilter filter = new TraceAuditFilter(
                properties,
                new AuditSanitizer(properties),
                operationLogService);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/upload/session-1/chunk");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("{\"password\":\"secret\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> res.getWriter().write("ok"));

        verify(operationLogService).logRequestAudit(
                isNull(),
                eq("UPLOAD"),
                eq("FILE"),
                isNull(),
                anyString(),
                any(),
                isNull(),
                eq(1),
                anyInt(),
                anyString(),
                anyString(),
                eq(200),
                isNull());
    }

    @Test
    void shouldInjectTraceparentWhenMissing() throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        TraceAuditFilter filter = new TraceAuditFilter(
                properties,
                new AuditSanitizer(properties),
                operationLogService);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
        request.addHeader(RequestTraceContext.HEADER_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceparent = new AtomicReference<>();

        filter.doFilter(request, response, new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) throws IOException {
                traceparent.set(((jakarta.servlet.http.HttpServletRequest) req).getHeader("traceparent"));
                res.getWriter().write("ok");
            }
        });

        assertThat(traceparent.get()).matches("00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01");
    }
}
