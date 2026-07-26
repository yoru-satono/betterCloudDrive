package com.betterclouddrive.storage.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageMetricsTest {

    @Test
    void recordsBoundedOperationOutcomesDurationsAndTransferredBytes() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new StorageMetrics(registry);

        assertThat(metrics.observe(StorageMetrics.Operation.PUT, () -> "stored")).isEqualTo("stored");
        assertThatThrownBy(() -> metrics.observeDownload(() -> {
            throw new IOException("storage unavailable");
        })).isInstanceOf(IOException.class);

        metrics.recordBytes(StorageMetrics.Direction.UPLOAD, 12);
        try (var input = metrics.observeDownload(
                () -> new ByteArrayInputStream(new byte[]{1, 2, 3, 4}))) {
            assertThat(input.readAllBytes()).hasSize(4);
        }
        var interrupted = metrics.observeDownload(() -> new InputStream() {
            private boolean first = true;

            @Override
            public int read() throws IOException {
                if (first) {
                    first = false;
                    return 7;
                }
                throw new IOException("stream interrupted");
            }
        });
        assertThat(interrupted.read()).isEqualTo(7);
        assertThatThrownBy(interrupted::read).isInstanceOf(IOException.class);
        interrupted.close();

        assertThat(counter(registry, "put", "success")).isEqualTo(1);
        assertThat(counter(registry, "get", "failure")).isEqualTo(2);
        assertThat(registry.find("bcd.storage.operation.duration")
                .tag("operation", "put").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.find("bcd.storage.operation.duration")
                .tag("operation", "get").tag("outcome", "failure").timer().count()).isEqualTo(2);
        assertThat(registry.find("bcd.storage.operation.duration")
                .tag("operation", "get").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.find("bcd.storage.bytes").tag("direction", "upload").counter().count()).isEqualTo(12);
        assertThat(registry.find("bcd.storage.bytes").tag("direction", "download").counter().count()).isEqualTo(5);

        assertThat(registry.find("bcd.storage.operations").counters()).hasSize(10);
        assertThat(registry.find("bcd.storage.bytes").counters()).hasSize(2);
    }

    private double counter(SimpleMeterRegistry registry, String operation, String outcome) {
        return registry.find("bcd.storage.operations")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
