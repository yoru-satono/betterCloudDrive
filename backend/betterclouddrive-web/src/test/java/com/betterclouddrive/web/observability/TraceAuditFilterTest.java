package com.betterclouddrive.web.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.context.RequestTraceContext;
import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.service.OperationLogService;
import com.betterclouddrive.web.config.ClientIpProperties;
import com.betterclouddrive.web.security.ClientIpResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
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
    void shouldRedactTeamInviteTokenFromAuditPath() {
        String token = "0123456789abcdef0123456789abcdef";

        assertThat(TraceAuditFilter.sanitizePath("/api/v1/team-invites/" + token))
                .isEqualTo("/api/v1/team-invites/***REDACTED***")
                .doesNotContain(token);
        assertThat(TraceAuditFilter.sanitizePath("/api/v1/team-invites/" + token + "/accept"))
                .isEqualTo("/api/v1/team-invites/***REDACTED***/accept")
                .doesNotContain(token);
    }

    @Test
    void shouldBindRequestIdAndWriteAudit() throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getAudit().setCaptureBody(true);
        ObjectMapper objectMapper = new ObjectMapper();
        TraceAuditFilter filter = new TraceAuditFilter(
                properties,
                new AuditSanitizer(properties),
                operationLogService, null, clientIpResolver());

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
        properties.getAudit().setCaptureBody(true);
        ObjectMapper objectMapper = new ObjectMapper();
        TraceAuditFilter filter = new TraceAuditFilter(
                properties,
                new AuditSanitizer(properties),
                operationLogService, null, clientIpResolver());

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
                operationLogService, null, clientIpResolver());

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

    @Test
    void shouldPreferTheExporterBackedCurrentSpan() throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties();
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        when(context.spanId()).thenReturn("bbbbbbbbbbbbbbbb");
        TraceAuditFilter filter = new TraceAuditFilter(
                properties,
                new AuditSanitizer(properties),
                operationLogService,
                tracer,
                clientIpResolver());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
        request.addHeader("traceparent", "00-11111111111111111111111111111111-2222222222222222-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> res.getWriter().write("ok"));

        assertThat(response.getHeader(RequestTraceContext.HEADER_TRACE_ID))
                .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        verify(operationLogService).logRequestAudit(
                isNull(), eq("READ"), eq("FILE"), isNull(), anyString(), any(), isNull(),
                eq(1), anyInt(), anyString(), eq("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), eq(200), isNull());
    }

    @Test
    void shouldTrustForwardedAddressOnlyFromConfiguredProxy() throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties();
        ClientIpProperties clientIpProperties = new ClientIpProperties();
        TraceAuditFilter filter = new TraceAuditFilter(
                properties, new AuditSanitizer(properties), operationLogService, null,
                new ClientIpResolver(clientIpProperties));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files");
        request.setRemoteAddr("203.0.113.5");
        request.addHeader("X-Forwarded-For", "198.51.100.7");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
        verify(operationLogService).logRequestAudit(
                isNull(), eq("READ"), eq("FILE"), isNull(), anyString(), eq("203.0.113.5"), isNull(),
                eq(1), anyInt(), anyString(), anyString(), eq(200), isNull());

        reset(operationLogService);
        clientIpProperties.setTrustedProxyCidrs(java.util.List.of("203.0.113.0/24"));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
        verify(operationLogService).logRequestAudit(
                isNull(), eq("READ"), eq("FILE"), isNull(), anyString(), eq("198.51.100.7"), isNull(),
                eq(1), anyInt(), anyString(), anyString(), eq(200), isNull());
    }

    @Test
    void shouldNeverCaptureInspectionBodiesEvenWhenCaptureIsEnabled() throws Exception {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getAudit().setCaptureBody(true);
        properties.getAudit().setBodyExcludedPathPrefixes(java.util.List.of());
        var filter = new TraceAuditFilter(properties, new AuditSanitizer(properties),
                operationLogService, null, clientIpResolver());
        for (String suffix : java.util.List.of("/runs", "/findings/1/reviews", "/quota-repairs", "/encoded")) {
            reset(operationLogService);
            var request = new MockHttpServletRequest("POST", "/api/v1/admin/storage-inspections" + suffix);
            if (suffix.equals("/encoded")) {
                request.setRequestURI("/drive/api/v1/admin/%73torage-inspections/findings/1/reviews");
                request.setContextPath("/drive");
                request.setServletPath("/api/v1/admin/storage-inspections/findings/1/reviews");
            }
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent("{\"reason\":\"private-inspection-note\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> req.getInputStream().readAllBytes());
            var detail = ArgumentCaptor.forClass(String.class);
            verify(operationLogService).logRequestAudit(isNull(), anyString(), anyString(), isNull(),
                    detail.capture(), any(), isNull(), eq(1), anyInt(), anyString(), anyString(), eq(200), isNull());
            var event = new ObjectMapper().readTree(detail.getValue());
            assertThat(event.get("request_body").isNull()).isTrue();
            assertThat(detail.getValue()).doesNotContain("private-inspection-note");
        }
    }

    private ClientIpResolver clientIpResolver() {
        return new ClientIpResolver(new ClientIpProperties());
    }
}
