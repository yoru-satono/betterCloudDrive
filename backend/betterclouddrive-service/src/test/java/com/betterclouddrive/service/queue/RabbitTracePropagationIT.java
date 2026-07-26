package com.betterclouddrive.service.queue;

import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.observability.AsyncTraceService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RabbitTracePropagationIT {

    @Container
    static final GenericContainer<?> RABBIT = new GenericContainer<>(
            DockerImageName.parse("rabbitmq:4.1-alpine"))
            .withExposedPorts(5672);

    @Test
    void propagatesPublisherContextThroughRealRabbitMqHeaders() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            var telemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .build();
            var traces = new AsyncTraceService(telemetry);

            var connection = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getMappedPort(5672));
            connection.setUsername("guest");
            connection.setPassword("guest");
            connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            connection.setPublisherReturns(true);
            try {
                var template = new RabbitTemplate(connection);
                template.setMandatory(true);
                template.setObservationEnabled(false);
                var admin = new RabbitAdmin(template);
                var properties = new QueueProperties();
                String queueName = "bcd.trace-it";
                var exchange = new TopicExchange(properties.getRabbitmq().getExchange(), true, false);
                admin.declareExchange(exchange);
                admin.declareQueue(new Queue(queueName, false, false, true));
                admin.declareBinding(BindingBuilder.bind(new Queue(queueName))
                        .to(exchange)
                        .with(properties.getRabbitmq().getTeamTasksRoutingKey()));

                var service = new RabbitQueueService(template, admin, new ObjectMapper(), properties, traces);
                Span request = telemetry.getTracer("test").spanBuilder("http.request").startSpan();
                AsyncTraceService.TraceContext requestLink;
                try (Scope ignored = request.makeCurrent()) {
                    requestLink = traces.capture();
                }
                request.end();

                String publisherSpanId;
                try (var publish = traces.startLinked(
                        "outbox.publish", requestLink, "team-tasks", "THUMBNAIL")) {
                    publisherSpanId = publish.spanContext().getSpanId();
                    assertThat(service.publish(
                            properties.getRabbitmq().getTeamTasksRoutingKey(),
                            Map.of("targetType", "THUMBNAIL"))).isNotBlank();
                    publish.success();
                }

                var message = template.receive(queueName, 5_000);
                assertThat(message).isNotNull();
                assertThat((Object) message.getMessageProperties().getHeader(AsyncTraceService.TRACEPARENT))
                        .isNotNull();
                try (var consume = traces.startConsumer(
                        "team_task.execute", message, "team-tasks", "THUMBNAIL")) {
                    consume.success();
                }

                var publisher = exporter.getFinishedSpanItems().stream()
                        .filter(span -> span.getName().equals("outbox.publish"))
                        .findFirst().orElseThrow();
                var consumer = exporter.getFinishedSpanItems().stream()
                        .filter(span -> span.getName().equals("team_task.execute"))
                        .findFirst().orElseThrow();
                assertThat(publisher.getLinks()).singleElement()
                        .satisfies(link -> assertThat(link.getSpanContext().getSpanId())
                                .isEqualTo(request.getSpanContext().getSpanId()));
                assertThat(consumer.getTraceId()).isEqualTo(publisher.getTraceId());
                assertThat(consumer.getParentSpanId()).isEqualTo(publisherSpanId);
            } finally {
                connection.destroy();
            }
        }
    }
}
