package com.betterclouddrive.storage.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class StorageMetrics {

    private static final String OPERATIONS = "bcd.storage.operations";
    private static final String DURATION = "bcd.storage.operation.duration";
    private static final String BYTES = "bcd.storage.bytes";

    private final MeterRegistry registry;
    private final Map<Direction, Counter> byteCounters = new EnumMap<>(Direction.class);

    public StorageMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Operation operation : Operation.values()) {
            for (Outcome outcome : Outcome.values()) {
                registry.counter(OPERATIONS, "operation", operation.tag(), "outcome", outcome.tag());
                registry.timer(DURATION, "operation", operation.tag(), "outcome", outcome.tag());
            }
        }
        for (Direction direction : Direction.values()) {
            byteCounters.put(direction, Counter.builder(BYTES)
                    .description("Object storage payload bytes transferred by direction")
                    .baseUnit("bytes")
                    .tag("direction", direction.tag())
                    .register(registry));
        }
    }

    public <T> T observe(Operation operation, StorageSupplier<T> supplier) throws Exception {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = supplier.get();
            record(operation, Outcome.SUCCESS, sample);
            return result;
        } catch (Exception e) {
            record(operation, Outcome.FAILURE, sample);
            throw e;
        }
    }

    public void observe(Operation operation, StorageAction action) throws Exception {
        observe(operation, () -> {
            action.run();
            return null;
        });
    }

    public void recordBytes(Direction direction, long bytes) {
        if (bytes > 0) {
            byteCounters.get(direction).increment(bytes);
        }
    }

    public InputStream observeDownload(StorageSupplier<InputStream> supplier) throws Exception {
        Timer.Sample sample = Timer.start(registry);
        try {
            return trackedDownload(supplier.get(), sample);
        } catch (Exception e) {
            record(Operation.GET, Outcome.FAILURE, sample);
            throw e;
        }
    }

    private InputStream trackedDownload(InputStream input, Timer.Sample sample) {
        return new FilterInputStream(input) {
            private final AtomicBoolean completed = new AtomicBoolean();
            private long bytesRead;

            @Override
            public int read() throws IOException {
                try {
                    int value = super.read();
                    if (value >= 0) {
                        bytesRead++;
                    } else {
                        complete(Outcome.SUCCESS);
                    }
                    return value;
                } catch (IOException e) {
                    complete(Outcome.FAILURE);
                    throw e;
                }
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                try {
                    int read = super.read(buffer, offset, length);
                    if (read > 0) {
                        bytesRead += read;
                    } else if (read < 0) {
                        complete(Outcome.SUCCESS);
                    }
                    return read;
                } catch (IOException e) {
                    complete(Outcome.FAILURE);
                    throw e;
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    super.close();
                    complete(Outcome.SUCCESS);
                } catch (IOException e) {
                    complete(Outcome.FAILURE);
                    throw e;
                }
            }

            private void complete(Outcome outcome) {
                if (completed.compareAndSet(false, true)) {
                    recordBytes(Direction.DOWNLOAD, bytesRead);
                    record(Operation.GET, outcome, sample);
                }
            }
        };
    }

    private void record(Operation operation, Outcome outcome, Timer.Sample sample) {
        registry.counter(OPERATIONS, "operation", operation.tag(), "outcome", outcome.tag()).increment();
        sample.stop(registry.timer(DURATION, "operation", operation.tag(), "outcome", outcome.tag()));
    }

    @FunctionalInterface
    public interface StorageSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface StorageAction {
        void run() throws Exception;
    }

    public enum Operation {
        PUT("put"),
        GET("get"),
        DELETE("delete"),
        HEAD("head"),
        COPY("copy");

        private final String tag;

        Operation(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    public enum Outcome {
        SUCCESS("success"),
        FAILURE("failure");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    public enum Direction {
        UPLOAD("upload"),
        DOWNLOAD("download");

        private final String tag;

        Direction(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }
}
