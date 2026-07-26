package com.betterclouddrive.service.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTraceServiceTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private OpenTelemetrySdk telemetry;
    private AsyncTraceService traces;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        telemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        traces = new AsyncTraceService(telemetry);
    }

    @AfterEach
    void tearDown() {
        provider.close();
        exporter.close();
    }

    @Test
    void injectsAndExtractsW3cContextAcrossAMessage() {
        Span producer = telemetry.getTracer("test").spanBuilder("publisher").startSpan();
        MessageProperties properties = new MessageProperties();
        try (Scope ignored = producer.makeCurrent()) {
            traces.injectCurrent(properties);
        }
        producer.end();

        assertThat(properties.getHeader(AsyncTraceService.TRACEPARENT).toString())
                .contains(producer.getSpanContext().getTraceId(), producer.getSpanContext().getSpanId());

        try (var operation = traces.startConsumer(
                "team_task.execute", new Message(new byte[0], properties), "team-tasks", "thumbnail")) {
            operation.success();
        }

        SpanData consumer = span("team_task.execute");
        assertThat(consumer.getTraceId()).isEqualTo(producer.getSpanContext().getTraceId());
        assertThat(consumer.getParentSpanId()).isEqualTo(producer.getSpanContext().getSpanId());
        assertThat(consumer.getAttributes().get(AttributeKey.stringKey("messaging.system"))).isEqualTo("rabbitmq");
        assertThat(consumer.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
                .isEqualTo("team-tasks");
        assertThat(consumer.getAttributes().get(AttributeKey.stringKey("bcd.task.type"))).isEqualTo("THUMBNAIL");
        assertThat(consumer.getAttributes().get(AttributeKey.stringKey("bcd.operation.outcome")))
                .isEqualTo("success");
    }

    @Test
    void delayedAndRetriedOperationsAreIndependentRootsLinkedToTheRequest() {
        Span request = telemetry.getTracer("test").spanBuilder("request").startSpan();
        AsyncTraceService.TraceContext link;
        try (Scope ignored = request.makeCurrent()) {
            link = traces.capture();
        }
        request.end();

        for (int attempt = 0; attempt < 2; attempt++) {
            try (var operation = traces.startLinked("outbox.publish", link, "team-tasks", "folder_zip")) {
                operation.retry();
            }
        }

        List<SpanData> attempts = exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("outbox.publish"))
                .toList();
        assertThat(attempts).hasSize(2);
        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.getParentSpanContext().isValid()).isFalse();
            assertThat(attempt.getLinks()).singleElement()
                    .satisfies(spanLink -> assertThat(spanLink.getSpanContext().getSpanId())
                            .isEqualTo(request.getSpanContext().getSpanId()));
            assertThat(attempt.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(attempt.getEvents()).extracting(event -> event.getName()).containsExactly("retry");
            assertThat(attempt.getAttributes().get(AttributeKey.stringKey("bcd.operation.outcome")))
                    .isEqualTo("retry");
        });
        assertThat(attempts).extracting(SpanData::getTraceId).doesNotHaveDuplicates();
    }

    @Test
    void boundsAttributesAndDoesNotCopyPayloadOrIdentifiers() {
        var link = new AsyncTraceService.TraceContext(
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                null);
        try (var operation = traces.startLinked(
                "outbox.publish", link, "private-team-42", "secret-file-name")) {
            operation.deadLetter();
        }

        SpanData span = span("outbox.publish");
        assertThat(span.getAttributes().asMap())
                .containsEntry(AttributeKey.stringKey("messaging.destination.name"), "unknown")
                .containsEntry(AttributeKey.stringKey("bcd.task.type"), "UNKNOWN")
                .containsEntry(AttributeKey.stringKey("bcd.operation.outcome"), "dead_letter")
                .doesNotContainValue("private-team-42")
                .doesNotContainValue("secret-file-name");
        assertThat(span.getEvents()).extracting(event -> event.getName()).containsExactly("dead_letter");
    }

    private SpanData span(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
