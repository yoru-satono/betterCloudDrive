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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class S3StorageServiceIT {

    @Container
    static final GenericContainer<?> SEAWEED = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:4.40"))
            .withCommand("server", "-s3", "-dir=/data", "-ip=0.0.0.0")
            .withExposedPorts(8333);

    static S3StorageServiceImpl storage;
    static S3InventoryService inventory;

    @BeforeAll
    static void createStorage() {
        var properties = new S3StorageProperties();
        properties.setEndpoint("http://" + SEAWEED.getHost() + ":" + SEAWEED.getMappedPort(8333));
        properties.setAccessKey("any");
        properties.setSecretKey("any");
        properties.setBucket("bcd-integration-test");
        storage = new S3StorageServiceImpl(
                properties,
                new StorageMetrics(new SimpleMeterRegistry(), io.opentelemetry.api.OpenTelemetry.noop()));
        storage.init();
        inventory = new S3InventoryService(properties,
                new StorageMetrics(new SimpleMeterRegistry(), io.opentelemetry.api.OpenTelemetry.noop()));
        inventory.init();
    }

    @Test
    void inventoryUsesBoundedPagesAndPreservesExactUnicodeKeys() {
        var properties = inventoryProperties();
        var metrics = new StorageMetrics(new SimpleMeterRegistry(), io.opentelemetry.api.OpenTelemetry.noop());
        var storage = new S3StorageServiceImpl(properties, metrics);
        storage.init();
        var inventory = new S3InventoryService(properties, metrics);
        inventory.init();
        var keys = java.util.List.of("a file.txt", "b_&.txt", "c%20d.txt", "c d.txt", "d+e.txt", "d%2Be.txt",
                "nested", "nested/file.txt", "\u6587\u4ef6.txt");
        try {
            for (String key : keys) storage.uploadObject(key, bytes("abc"), 3, "text/plain");
            var found = new java.util.ArrayList<String>();
            var tokens = new java.util.HashSet<String>();
            String token = null;
            for (int index = 0; index < 20; index++) {
                var page = inventory.list(token, 2);
                assertThat(page.objects()).hasSizeLessThanOrEqualTo(2);
                page.objects().forEach(object -> found.add(object.key()));
                if (page.complete()) break;
                token = page.nextToken();
                assertThat(token).isNotBlank();
                assertThat(tokens.add(token)).isTrue();
            }
            // SeaweedFS promotes a file into a directory entry when children are added.
            // Keep its actual inventory key; the inspector detects an unlisted-but-readable reference as partial coverage.
            var listedKeys = keys.stream().map(key -> key.equals("nested") ? "nested/" : key).toList();
            assertThat(found).containsExactlyInAnyOrderElementsOf(listedKeys);
            assertThat(inventory.stat("nested")).isPresent();
            assertThat(inventory.stat(keys.getFirst()).orElseThrow().size()).isEqualTo(3);
            assertThat(inventory.stat("absent")).isEmpty();
        } finally { keys.forEach(storage::deleteObject); }
    }

    private S3StorageProperties inventoryProperties() {
        var properties = new S3StorageProperties();
        properties.setEndpoint("http://" + SEAWEED.getHost() + ":" + SEAWEED.getMappedPort(8333));
        properties.setAccessKey("any"); properties.setSecretKey("any");
        properties.setBucket("bcd-inventory-" + UUID.randomUUID().toString().substring(0, 8));
        return properties;
    }

    @Test
    void emptyInventoryIsCompleteButAMissingBucketIsNotAnEmptyBucket() {
        var properties = inventoryProperties();
        var metrics = new StorageMetrics(new SimpleMeterRegistry(), io.opentelemetry.api.OpenTelemetry.noop());
        var inventory = new S3InventoryService(properties, metrics);
        inventory.init();
        assertThatThrownBy(() -> inventory.list(null, 2))
                .isInstanceOf(com.betterclouddrive.storage.StorageInventoryException.class);
        assertThatThrownBy(() -> inventory.stat("fixture"))
                .isInstanceOf(com.betterclouddrive.storage.StorageInventoryException.class);
        var storage = new S3StorageServiceImpl(properties, metrics);
        storage.init();
        var empty = inventory.list(null, 2);
        assertThat(empty.objects()).isEmpty();
        assertThat(empty.complete()).isTrue();
    }

    @Test
    void inventoryTransportFailureDoesNotMeanObjectMissing() {
        var unavailable = new S3StorageProperties();
        unavailable.setEndpoint("http://127.0.0.1:1");
        unavailable.setAccessKey("fixture"); unavailable.setSecretKey("fixture");
        var disconnected = new S3InventoryService(unavailable,
                new StorageMetrics(new SimpleMeterRegistry(), io.opentelemetry.api.OpenTelemetry.noop()));
        disconnected.init();
        assertThatThrownBy(() -> disconnected.stat("fixture"))
                .isInstanceOf(com.betterclouddrive.storage.StorageInventoryException.class);
        assertThatThrownBy(() -> disconnected.list(null, 10))
                .isInstanceOf(com.betterclouddrive.storage.StorageInventoryException.class);
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
    void composesPartsInOrderAndPreservesPartsUntilExplicitCleanup() throws Exception {
        String prefix = "it/" + UUID.randomUUID() + "/upload";
        storage.uploadPart(prefix, 0, bytes("hello "), 6);
        storage.uploadPart(prefix, 1, bytes("world"), 5);

        String result = prefix + "/result.txt";
        var digest = storage.composeParts(result, prefix, 2);
        assertThat(digest.size()).isEqualTo(11);
        assertThat(digest.md5()).isEqualTo("5eb63bbbe01eeed093cb22bb8f5acdc3");
        assertThat(storage.inspectObject(result)).isEqualTo(digest);
        assertThat(storage.objectExists(prefix + ".part.1")).isTrue();
        storage.deleteParts(prefix, 2);

        try (var stream = storage.downloadObject(result)) {
            assertThat(stream.readAllBytes()).isEqualTo("hello world".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(storage.objectExists(prefix + ".part.1")).isFalse();
        assertThat(storage.objectExists(prefix + ".part.2")).isFalse();
        storage.deleteObject(result);
    }

    @Test
    void composesAcrossSdkMultipartBoundaryWithExactDigest() throws Exception {
        String prefix = "it/" + UUID.randomUUID() + "/large";
        byte[] part = new byte[5 * 1024 * 1024];
        java.util.Arrays.fill(part, (byte) 17);
        byte[] tail = "last-seventeen!!!".getBytes(StandardCharsets.UTF_8);
        var expected = java.security.MessageDigest.getInstance("MD5");
        for (int i = 0; i < 2; i++) {
            storage.uploadPart(prefix, i, new ByteArrayInputStream(part), part.length);
            expected.update(part);
        }
        storage.uploadPart(prefix, 2, new ByteArrayInputStream(tail), tail.length);
        expected.update(tail);
        String result = prefix + "/result";
        try {
            var digest = storage.composeParts(result, prefix, 3);
            assertThat(digest.size()).isEqualTo(2L * part.length + tail.length);
            assertThat(digest.md5()).isEqualTo(java.util.HexFormat.of().formatHex(expected.digest()));
            assertThat(storage.inspectObject(result)).isEqualTo(digest);
            assertThat(storage.objectExists(prefix + ".part.3")).isTrue();
        } finally {
            storage.deleteParts(prefix, 3);
            storage.deleteObject(result);
        }
    }

    @Test
    void failedCompositionPreservesAvailablePartsAndMissingObjectHasSpecificError() {
        String prefix = "it/" + UUID.randomUUID() + "/incomplete";
        storage.uploadPart(prefix, 0, bytes("hello"), 5);
        String result = prefix + "/result";
        try {
            assertThatThrownBy(() -> storage.composeParts(result, prefix, 2)).isInstanceOf(RuntimeException.class);
            assertThat(storage.objectExists(prefix + ".part.1")).isTrue();
            assertThatThrownBy(() -> storage.inspectObject(prefix + ".part.2"))
                    .isInstanceOf(com.betterclouddrive.storage.ObjectNotFoundException.class);
        } finally {
            storage.deleteParts(prefix, 2);
            storage.deleteObject(result);
        }
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
