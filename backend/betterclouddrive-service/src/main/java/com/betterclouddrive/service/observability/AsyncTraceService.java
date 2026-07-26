package com.betterclouddrive.service.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AsyncTraceService {

    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";

    private static final AttributeKey<String> MESSAGING_SYSTEM = AttributeKey.stringKey("messaging.system");
    private static final AttributeKey<String> MESSAGING_DESTINATION =
            AttributeKey.stringKey("messaging.destination.name");
    private static final AttributeKey<String> TASK_TYPE = AttributeKey.stringKey("bcd.task.type");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("bcd.operation.outcome");
    private static final Set<String> DESTINATIONS = Set.of("notifications", "team-tasks");
    private static final Set<String> TASK_TYPES =
            Set.of("AUDIT_EXPORT", "THUMBNAIL", "FOLDER_ZIP", "ONLYOFFICE_SAVE");

    private static final TextMapGetter<MessageProperties> MESSAGE_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(MessageProperties carrier) {
            return carrier == null ? List.of() : carrier.getHeaders().keySet();
        }

        @Override
        public String get(MessageProperties carrier, String key) {
            if (carrier == null) {
                return null;
            }
            Object value = carrier.getHeaders().get(key);
            return value == null ? null : String.valueOf(value);
        }
    };

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public AsyncTraceService(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.tracer = openTelemetry.getTracer("com.betterclouddrive.async");
    }

    public TraceContext capture() {
        if (!Span.current().getSpanContext().isValid()) {
            return TraceContext.empty();
        }
        Map<String, String> values = new java.util.HashMap<>();
        openTelemetry.getPropagators().getTextMapPropagator().inject(
                Context.current(),
                values,
                Map::put);
        return new TraceContext(values.get(TRACEPARENT), values.get(TRACESTATE));
    }

    public void injectCurrent(MessageProperties properties) {
        openTelemetry.getPropagators().getTextMapPropagator().inject(
                Context.current(),
                properties,
                MessageProperties::setHeader);
    }

    public TraceOperation startLinked(
            String name,
            TraceContext link,
            String destination,
            String taskType) {
        SpanBuilder builder = tracer.spanBuilder(name).setNoParent().setSpanKind(SpanKind.PRODUCER);
        SpanContext linkedContext = spanContext(link);
        if (linkedContext.isValid()) {
            builder.addLink(linkedContext);
        }
        return start(builder, destination, taskType);
    }

    public TraceOperation startConsumer(
            String name,
            Message message,
            String destination,
            String taskType) {
        Context extracted = openTelemetry.getPropagators().getTextMapPropagator().extract(
                Context.root(),
                message == null ? null : message.getMessageProperties(),
                MESSAGE_GETTER);
        SpanBuilder builder = tracer.spanBuilder(name)
                .setParent(extracted)
                .setSpanKind(SpanKind.CONSUMER);
        return start(builder, destination, taskType);
    }

    public TraceOperation startLinkedTask(String name, TraceContext link, String taskType) {
        SpanBuilder builder = tracer.spanBuilder(name).setNoParent().setSpanKind(SpanKind.INTERNAL);
        SpanContext linkedContext = spanContext(link);
        if (linkedContext.isValid()) {
            builder.addLink(linkedContext);
        }
        return start(builder, null, taskType);
    }

    private TraceOperation start(SpanBuilder builder, String destination, String taskType) {
        Span span = builder.startSpan();
        if (destination != null) {
            span.setAttribute(MESSAGING_SYSTEM, "rabbitmq");
            span.setAttribute(MESSAGING_DESTINATION, normalizeDestination(destination));
        }
        if (taskType != null) {
            span.setAttribute(TASK_TYPE, normalizeTaskType(taskType));
        }
        return new TraceOperation(span, span.makeCurrent());
    }

    private SpanContext spanContext(TraceContext carrier) {
        if (carrier == null || !StringUtils.hasText(carrier.traceparent())) {
            return SpanContext.getInvalid();
        }
        Map<String, String> values = StringUtils.hasText(carrier.tracestate())
                ? Map.of(TRACEPARENT, carrier.traceparent(), TRACESTATE, carrier.tracestate())
                : Map.of(TRACEPARENT, carrier.traceparent());
        Context extracted = openTelemetry.getPropagators().getTextMapPropagator().extract(
                Context.root(),
                values,
                new TextMapGetter<>() {
                    @Override
                    public Iterable<String> keys(Map<String, String> source) {
                        return source.keySet();
                    }

                    @Override
                    public String get(Map<String, String> source, String key) {
                        return source.get(key);
                    }
                });
        return Span.fromContext(extracted).getSpanContext();
    }

    private static String normalizeDestination(String destination) {
        String normalized = destination.trim().toLowerCase(Locale.ROOT);
        return DESTINATIONS.contains(normalized) ? normalized : "unknown";
    }

    private static String normalizeTaskType(String taskType) {
        String normalized = taskType.trim().toUpperCase(Locale.ROOT);
        return TASK_TYPES.contains(normalized) ? normalized : "UNKNOWN";
    }

    public record TraceContext(String traceparent, String tracestate) {
        public static TraceContext empty() {
            return new TraceContext(null, null);
        }
    }

    public static final class TraceOperation implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private final AtomicBoolean completed = new AtomicBoolean();

        private TraceOperation(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public SpanContext spanContext() {
            return span.getSpanContext();
        }

        public void taskType(String taskType) {
            if (taskType != null && !completed.get()) {
                span.setAttribute(TASK_TYPE, normalizeTaskType(taskType));
            }
        }

        public void success() {
            finish("success", null);
        }

        public void failure() {
            finish("failure", StatusCode.ERROR);
        }

        public void retry() {
            span.addEvent("retry");
            finish("retry", StatusCode.ERROR);
        }

        public void deadLetter() {
            span.addEvent("dead_letter");
            finish("dead_letter", StatusCode.ERROR);
        }

        private void finish(String outcome, StatusCode status) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            span.setAttribute(OUTCOME, outcome);
            if (status != null) {
                span.setStatus(status);
            }
            scope.close();
            span.end();
        }

        @Override
        public void close() {
            if (!completed.get()) {
                failure();
            }
        }
    }
}
