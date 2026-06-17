package com.betterclouddrive.web.observability;

import com.betterclouddrive.common.context.RequestTraceContext;
import com.betterclouddrive.service.OperationLogService;
import com.betterclouddrive.web.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TraceAuditFilter extends OncePerRequestFilter {

    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("AUDIT");
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern SPAN_ID_PATTERN = Pattern.compile("^[0-9a-fA-F]{16}$");

    private final ObservabilityProperties properties;
    private final AuditSanitizer auditSanitizer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OperationLogService operationLogService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        long startNanos = System.nanoTime();
        String requestId = resolveRequestId(request);
        TraceIds traceIds = resolveTraceIds(request);
        HttpServletRequest traceRequest = traceIds.incomingTraceparent()
                ? request
                : new TraceParentRequestWrapper(request, traceIds.traceparent());
        HttpServletRequest auditRequest = shouldCaptureBody(traceRequest)
                ? new ContentCachingRequestWrapper(traceRequest, properties.getAudit().getMaxBodyBytes())
                : traceRequest;

        bindContext(auditRequest, requestId, traceIds);
        response.setHeader(RequestTraceContext.HEADER_REQUEST_ID, requestId);
        response.setHeader(RequestTraceContext.HEADER_TRACE_ID, traceIds.traceId());

        Throwable failure = null;
        try {
            filterChain.doFilter(auditRequest, response);
        } catch (ServletException | IOException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            try {
                writeAudit(auditRequest, response, startNanos, requestId, traceIds, failure);
            } finally {
                MDC.clear();
                RequestTraceContext.clear();
            }
        }
    }

    private void bindContext(HttpServletRequest request, String requestId, TraceIds traceIds) {
        RequestTraceContext.setRequestId(requestId);
        RequestTraceContext.setTraceId(traceIds.traceId());
        RequestTraceContext.setSpanId(traceIds.spanId());

        putMdc(RequestTraceContext.MDC_REQUEST_ID, requestId);
        putMdc(RequestTraceContext.MDC_TRACE_ID, traceIds.traceId());
        putMdc(RequestTraceContext.MDC_SPAN_ID, traceIds.spanId());
        putMdc(RequestTraceContext.MDC_METHOD, request.getMethod());
        putMdc(RequestTraceContext.MDC_PATH, request.getRequestURI());
        putMdc(RequestTraceContext.MDC_CLIENT_IP, clientIp(request));

        putMdc("requestId", requestId);
        putMdc("traceId", traceIds.traceId());
        putMdc("spanId", traceIds.spanId());
    }

    private void writeAudit(HttpServletRequest request, HttpServletResponse response, long startNanos,
                            String requestId, TraceIds traceIds, Throwable failure) {
        if (!properties.getAudit().isEnabled() || !shouldAudit(request)) {
            return;
        }

        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        Long userId = currentUserId(request);
        if (userId != null) {
            putMdc(RequestTraceContext.MDC_USER_ID, String.valueOf(userId));
            putMdc("userId", String.valueOf(userId));
        }

        int status = response.getStatus();
        String action = actionType(request);
        String targetType = targetType(request);
        Map<String, Object> event = auditEvent(request, response, durationMs, requestId, traceIds,
                userId, action, targetType, failure);
        String detail = serializeAuditEvent(event);

        operationLogService.logRequestAudit(
                userId,
                action,
                targetType,
                null,
                detail,
                clientIp(request),
                request.getHeader("User-Agent"),
                failure == null && status < 400 ? 1 : 0,
                (int) Math.min(durationMs, Integer.MAX_VALUE),
                requestId,
                traceIds.traceId(),
                status,
                null);

        if (properties.getAudit().isFullEventEnabled()) {
            writeFullAuditEvent(event);
        }
    }

    private Map<String, Object> auditEvent(HttpServletRequest request, HttpServletResponse response, long durationMs,
                                           String requestId, TraceIds traceIds, Long userId, String action,
                                           String targetType, Throwable failure) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_type", "http_audit");
        event.put("timestamp", Instant.now().toString());
        event.put("service", "better-cloud-drive");
        event.put("request_id", requestId);
        event.put("trace_id", traceIds.traceId());
        event.put("span_id", traceIds.spanId());
        event.put("user_id", userId);
        event.put("action_type", action);
        event.put("target_type", targetType);
        event.put("method", request.getMethod());
        event.put("path", request.getRequestURI());
        event.put("query", request.getQueryString());
        event.put("status", response.getStatus());
        event.put("result", failure == null && response.getStatus() < 400 ? "success" : "failure");
        event.put("duration_ms", durationMs);
        event.put("client_ip", clientIp(request));
        event.put("user_agent", request.getHeader("User-Agent"));
        event.put("headers", auditSanitizer.sanitizeHeaders(headers(request)));
        event.put("request_body", requestBody(request));
        if (failure != null) {
            event.put("error_type", failure.getClass().getName());
            event.put("error_message", failure.getMessage());
        }
        return event;
    }

    private void writeFullAuditEvent(Map<String, Object> event) {
        try {
            AUDIT_LOGGER.info(objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Failed to serialize audit event: {}", e.getMessage());
        }
    }

    private String serializeAuditEvent(Map<String, Object> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{\"message\":\"audit\"}";
        }
    }

    private boolean shouldAudit(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/") || path.startsWith("/webdav");
    }

    private boolean shouldCaptureBody(HttpServletRequest request) {
        if (!properties.getAudit().isFullEventEnabled()) {
            return false;
        }
        String path = request.getRequestURI();
        boolean excluded = properties.getAudit().getBodyExcludedPathPrefixes().stream()
                .anyMatch(path::startsWith);
        if (excluded) {
            return false;
        }
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith(MediaType.APPLICATION_JSON_VALUE)
                || normalized.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                || normalized.startsWith(MediaType.TEXT_PLAIN_VALUE);
    }

    private String requestBody(HttpServletRequest request) {
        if (request instanceof ContentCachingRequestWrapper wrapper) {
            return auditSanitizer.sanitizeBody(wrapper.getContentAsByteArray());
        }
        return null;
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(RequestTraceContext.HEADER_REQUEST_ID);
        if (StringUtils.hasText(incoming) && incoming.length() <= 64) {
            return incoming;
        }
        return RequestTraceContext.generateRequestId();
    }

    private TraceIds resolveTraceIds(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        if (StringUtils.hasText(traceparent)) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 4 && isTraceId(parts[1]) && isSpanId(parts[2])) {
                return new TraceIds(parts[1].toLowerCase(Locale.ROOT), parts[2].toLowerCase(Locale.ROOT), true);
            }
        }

        String incomingTraceId = request.getHeader(RequestTraceContext.HEADER_TRACE_ID);
        String traceId = isTraceId(incomingTraceId)
                ? incomingTraceId.toLowerCase(Locale.ROOT)
                : RequestTraceContext.generateTraceId();
        return new TraceIds(traceId, RequestTraceContext.generateSpanId(), false);
    }

    private boolean isTraceId(String value) {
        return StringUtils.hasText(value) && TRACE_ID_PATTERN.matcher(value).matches()
                && !"00000000000000000000000000000000".equals(value);
    }

    private boolean isSpanId(String value) {
        return StringUtils.hasText(value) && SPAN_ID_PATTERN.matcher(value).matches()
                && !"0000000000000000".equals(value);
    }

    private Long currentUserId(HttpServletRequest request) {
        if (request != null) {
            Object attribute = request.getAttribute(RequestTraceContext.ATTRIBUTE_USER_ID);
            if (attribute instanceof Long userId) {
                return userId;
            }
            if (attribute instanceof Number number) {
                return number.longValue();
            }
            if (attribute instanceof String text && StringUtils.hasText(text)) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getUserId();
        }
        return null;
    }

    private String actionType(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI().toLowerCase(Locale.ROOT);
        if (path.contains("/auth/login")) return "LOGIN";
        if (path.contains("/auth/register")) return "REGISTER";
        if (path.contains("/download") || "GET".equals(method) && path.startsWith("/webdav")) return "DOWNLOAD";
        if (path.contains("/upload") || "PUT".equals(method) && path.startsWith("/webdav")) return "UPLOAD";
        if ("DELETE".equals(method)) return "DELETE";
        if (path.contains("/share")) return "SHARE";
        if (path.contains("/restore")) return "RESTORE";
        if ("MOVE".equals(method) || path.contains("/move")) return "MOVE";
        if ("COPY".equals(method) || path.contains("/copy")) return "COPY";
        if (path.contains("/rename")) return "RENAME";
        if ("GET".equals(method) || "PROPFIND".equals(method) || "HEAD".equals(method)) return "READ";
        return "OTHER";
    }

    private String targetType(HttpServletRequest request) {
        String path = request.getRequestURI().toLowerCase(Locale.ROOT);
        if (path.contains("/shares")) return "SHARE_LINK";
        if (path.contains("/users") || path.contains("/auth")) return "USER";
        if (path.contains("/folders") || path.endsWith("/zip")) return "FOLDER";
        return "FILE";
    }

    private Map<String, String> headers(HttpServletRequest request) {
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    private void putMdc(String key, String value) {
        if (StringUtils.hasText(value)) {
            MDC.put(key, value);
        }
    }

    private record TraceIds(String traceId, String spanId, boolean incomingTraceparent) {

        private String traceparent() {
            return "00-" + traceId + "-" + spanId + "-01";
        }
    }

    private static final class TraceParentRequestWrapper extends HttpServletRequestWrapper {

        private final String traceparent;

        private TraceParentRequestWrapper(HttpServletRequest request, String traceparent) {
            super(request);
            this.traceparent = traceparent;
        }

        @Override
        public String getHeader(String name) {
            if ("traceparent".equalsIgnoreCase(name)) {
                return traceparent;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("traceparent".equalsIgnoreCase(name)) {
                return Collections.enumeration(java.util.List.of(traceparent));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            java.util.List<String> names = Collections.list(super.getHeaderNames());
            boolean hasTraceparent = names.stream().anyMatch("traceparent"::equalsIgnoreCase);
            if (!hasTraceparent) {
                names.add("traceparent");
            }
            return Collections.enumeration(names);
        }
    }
}
