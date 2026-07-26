package com.betterclouddrive.storage.s3;

import com.betterclouddrive.storage.metrics.StorageMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class S3StorageServiceIT {

    @Container
    static final GenericContainer<?> SEAWEED = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:latest"))
            .withCommand("server", "-s3", "-dir=/data", "-ip=0.0.0.0")
            .withExposedPorts(8333);

    static S3StorageServiceImpl storage;

    @BeforeAll
    static void createStorage() {
        var properties = new S3StorageProperties();
        properties.setEndpoint("http://" + SEAWEED.getHost() + ":" + SEAWEED.getMappedPort(8333));
        properties.setAccessKey("any");
        properties.setSecretKey("any");
        properties.setBucket("bcd-integration-test");
        storage = new S3StorageServiceImpl(properties, new StorageMetrics(new SimpleMeterRegistry()));
        storage.init();
    }

    @Test
    void supportsObjectLifecycleCopyAndRangeReads() throws Exception {
        String prefix = "it/" + UUID.randomUUID();
        String source = prefix + "/source.txt";
        String copy = prefix + "/copy.txt";
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);

        assertThat(storage.uploadObject(source, new ByteArrayInputStream(content), content.length, "text/plain"))
                .isEqualTo(source);
        assertThat(storage.objectExists(source)).isTrue();
        assertThat(storage.getObjectSize(source)).isEqualTo(content.length);
        try (var stream = storage.downloadObjectRange(source, 2, 4)) {
            assertThat(stream.readAllBytes()).isEqualTo("2345".getBytes(StandardCharsets.UTF_8));
        }

        storage.copyObject(source, copy);
        try (var stream = storage.downloadObject(copy)) {
            assertThat(stream.readAllBytes()).isEqualTo(content);
        }
        storage.deleteObject(source);
        storage.deleteObject(copy);
        assertThat(storage.objectExists(source)).isFalse();
    }

    @Test
    void composesPartsInOrderAndRemovesTemporaryObjects() throws Exception {
        String prefix = "it/" + UUID.randomUUID() + "/upload";
        storage.uploadPart(prefix, 0, bytes("hello "), 6);
        storage.uploadPart(prefix, 1, bytes("world"), 5);

        String result = prefix + "/result.txt";
        storage.composeParts(result, prefix, 2);

        try (var stream = storage.downloadObject(result)) {
            assertThat(stream.readAllBytes()).isEqualTo("hello world".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(storage.objectExists(prefix + ".part.1")).isFalse();
        assertThat(storage.objectExists(prefix + ".part.2")).isFalse();
        storage.deleteObject(result);
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
