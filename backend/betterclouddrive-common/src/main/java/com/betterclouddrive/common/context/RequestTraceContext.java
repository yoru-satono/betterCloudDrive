package com.betterclouddrive.common.context;

import java.util.UUID;

public final class RequestTraceContext {

    public static final String MDC_REQUEST_ID = "request_id";
    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SPAN_ID = "span_id";
    public static final String MDC_USER_ID = "user_id";
    public static final String MDC_METHOD = "method";
    public static final String MDC_PATH = "path";
    public static final String MDC_CLIENT_IP = "client_ip";
    public static final String HEADER_REQUEST_ID = "X-Request-ID";
    public static final String HEADER_TRACE_ID = "X-Trace-ID";
    public static final String ATTRIBUTE_USER_ID = RequestTraceContext.class.getName() + ".USER_ID";

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SPAN_ID = new ThreadLocal<>();

    private RequestTraceContext() {}

    public static void setRequestId(String requestId) {
        REQUEST_ID.set(requestId);
    }

    public static String getRequestId() {
        String requestId = REQUEST_ID.get();
        return requestId != null ? requestId : generateRequestId();
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static void setSpanId(String spanId) {
        SPAN_ID.set(spanId);
    }

    public static String getSpanId() {
        return SPAN_ID.get();
    }

    public static void clear() {
        REQUEST_ID.remove();
        TRACE_ID.remove();
        SPAN_ID.remove();
    }

    public static String generateRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
