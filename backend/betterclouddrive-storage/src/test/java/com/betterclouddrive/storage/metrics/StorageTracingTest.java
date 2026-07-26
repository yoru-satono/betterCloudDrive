package com.betterclouddrive.storage.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageTracingTest {

    @Test
    void recordsPutAndStreamingGetOutcomesWithoutObjectKeys() throws Exception {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var metrics = new StorageMetrics(new SimpleMeterRegistry(), telemetry);

            metrics.observe(StorageMetrics.Operation.PUT, () -> "objects/private-name");
            try (var input = metrics.observeDownload(
                    () -> new ByteArrayInputStream(new byte[]{1, 2, 3}))) {
                assertThat(input.readAllBytes()).containsExactly(1, 2, 3);
            }
            assertThatThrownBy(() -> metrics.observe(StorageMetrics.Operation.PUT, () -> {
                throw new IllegalStateException("secret-object-key");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(exporter.getFinishedSpanItems()).extracting(span -> span.getName())
                    .containsExactly("storage.put", "storage.get", "storage.put");
            assertThat(exporter.getFinishedSpanItems()).allSatisfy(span ->
                    assertThat(span.getAttributes().asMap().keySet())
                            .containsExactly(AttributeKey.stringKey("bcd.operation.outcome")));
            assertThat(exporter.getFinishedSpanItems().get(2).getStatus().getStatusCode())
                    .isEqualTo(StatusCode.ERROR);
            assertThat(exporter.getFinishedSpanItems().toString())
                    .doesNotContain("private-name", "secret-object-key");
        }
    }
}
