package com.betterclouddrive.storage.s3;

import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.storage.ContentDigest;
import com.betterclouddrive.storage.ObjectNotFoundException;
import io.minio.errors.ErrorResponseException;
import com.betterclouddrive.storage.metrics.StorageMetrics;
import io.minio.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Enumeration;
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageServiceImpl implements StorageService {

    private static final long COMPOSE_UPLOAD_PART_SIZE = 10L * 1024 * 1024;

    private final S3StorageProperties props;
    private final StorageMetrics metrics;
    private MinioClient client;

    @PostConstruct
    public void init() {
        this.client = MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .region(props.getRegion())
                .build();
        // Ensure bucket exists
        try {
            boolean found = client.bucketExists(BucketExistsArgs.builder().bucket(props.getBucket()).build());
            if (!found) {
                client.makeBucket(MakeBucketArgs.builder().bucket(props.getBucket()).build());
            }
        } catch (Exception e) {
            log.error("Failed to initialize storage bucket", safeFailure("initialize", e));
        }
    }

    @Override
    public String uploadObject(String objectKey, InputStream inputStream, long size, String contentType) {
        try {
            metrics.observe(StorageMetrics.Operation.PUT, () -> client.putObject(
                    PutObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(objectKey)
                            .stream(inputStream, size, -1L)
                            .contentType(contentType)
                            .build()));
            metrics.recordBytes(StorageMetrics.Direction.UPLOAD, size);
            return objectKey;
        } catch (Exception e) {
            var failure = safeFailure("upload", e);
            log.error("Storage upload failed", failure);
            throw failure;
        }
    }

    @Override
    public InputStream downloadObject(String objectKey) {
        try {
            return metrics.observeDownload(() -> client.getObject(
                    GetObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(objectKey)
                            .build()));
        } catch (Exception e) {
            if (e instanceof ErrorResponseException error
                    && ("NoSuchKey".equals(error.errorResponse().code()) || "NoSuchObject".equals(error.errorResponse().code()))) {
                throw new ObjectNotFoundException(safeFailure("download", e));
            }
            var failure = safeFailure("download", e);
            log.error("Storage download failed", failure);
            throw failure;
        }
    }

    @Override
    public InputStream downloadObjectRange(String objectKey, long offset, long length) {
        try {
            return metrics.observeDownload(() -> client.getObject(
                    GetObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(objectKey)
                            .offset(offset)
                            .length(length)
                            .build()));
        } catch (Exception e) {
            var failure = safeFailure("range-download", e);
            log.error("Storage range download failed", failure);
            throw failure;
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        try {
            metrics.observe(StorageMetrics.Operation.DELETE, () -> client.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(objectKey)
                            .build()));
        } catch (Exception e) {
            log.warn("Storage delete failed", safeFailure("delete", e));
        }
    }

    @Override
    public String uploadPart(String prefix, int partNumber, InputStream data, long size) {
        String partKey = prefix + ".part." + (partNumber + 1);
        try {
            metrics.observe(StorageMetrics.Operation.PUT, () -> client.putObject(
                    PutObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(partKey)
                            .stream(data, size, -1L)
                            .build()));
            metrics.recordBytes(StorageMetrics.Direction.UPLOAD, size);
            return partKey;
        } catch (Exception e) {
            var failure = safeFailure("part-upload", e);
            log.error("Storage part upload failed", failure);
            throw failure;
        }
    }

    @Override
    public ContentDigest composeParts(String finalObjectKey, String sourcePrefix, int totalParts) {
        try {
            return uploadComposedObject(finalObjectKey, sourcePrefix, totalParts);
        } catch (Exception e) {
            var failure = safeFailure("compose", e);
            log.error("Storage composition failed", failure);
            throw failure;
        }
    }

    private ContentDigest uploadComposedObject(String finalObjectKey, String sourcePrefix, int totalParts) throws Exception {
        try (ContentDigest.Stream stream = new ContentDigest.Stream(new SequenceInputStream(partStreams(sourcePrefix, totalParts)))) {
            metrics.observe(StorageMetrics.Operation.PUT, () -> client.putObject(
                    PutObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(finalObjectKey)
                            .stream(stream, -1L, COMPOSE_UPLOAD_PART_SIZE)
                            .build()));
            return stream.result();
        }
    }

    private Enumeration<InputStream> partStreams(String sourcePrefix, int totalParts) {
        return new Enumeration<>() {
            private int index = 0;

            @Override
            public boolean hasMoreElements() {
                return index < totalParts;
            }

            @Override
            public InputStream nextElement() {
                if (!hasMoreElements()) {
                    throw new NoSuchElementException();
                }
                String partKey = partKey(sourcePrefix, index++);
                try {
                    return metrics.observeDownload(() -> client.getObject(
                            GetObjectArgs.builder()
                                    .bucket(props.getBucket())
                                    .object(partKey)
                                    .build()));
                } catch (Exception e) {
                    throw safeFailure("part-read", e);
                }
            }
        };
    }

    private String partKey(String sourcePrefix, int zeroBasedPartNumber) {
        return sourcePrefix + ".part." + (zeroBasedPartNumber + 1);
    }

    @Override
    public void deleteParts(String prefix, int maxParts) {
        for (int i = 1; i <= maxParts; i++) {
            deleteObject(prefix + ".part." + i);
        }
    }

    @Override
    public boolean objectExists(String objectKey) {
        try {
            metrics.observe(StorageMetrics.Operation.HEAD,
                    () -> client.statObject(StatObjectArgs.builder().bucket(props.getBucket()).object(objectKey).build()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void copyObject(String sourceKey, String destKey) {
        try {
            metrics.observe(StorageMetrics.Operation.COPY, () -> client.copyObject(
                    CopyObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(destKey)
                            .source(SourceObject.builder().bucket(props.getBucket()).object(sourceKey).build())
                            .build()));
        } catch (Exception e) {
            var failure = safeFailure("copy", e);
            log.error("Storage copy failed", failure);
            throw failure;
        }
    }

    @Override
    public long getObjectSize(String objectKey) {
        try {
            return metrics.observe(StorageMetrics.Operation.HEAD, () -> client.statObject(StatObjectArgs.builder()
                    .bucket(props.getBucket()).object(objectKey).build()).size());
        } catch (Exception e) {
            log.warn("Storage size lookup failed", safeFailure("size", e));
            return -1;
        }
    }
    /** SDK errors embed signed HTTP headers; retain failure category and stack locations only. */
    static RuntimeException safeFailure(String operation, Exception error) {
        if (error instanceof SanitizedFailure safe) return safe;
        Throwable root = error;
        for (int depth = 0; depth < 16 && root.getCause() != null && root.getCause() != root; depth++) root = root.getCause();
        if (root instanceof InterruptedException) Thread.currentThread().interrupt();
        String code = root instanceof ErrorResponseException remote ? remote.errorResponse().code() : "unavailable";
        if (code == null || !code.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) code = "unavailable";
        var safe = new SanitizedFailure("Storage " + operation + " failed; type=" + root.getClass().getSimpleName() + "; code=" + code);
        safe.setStackTrace(error.getStackTrace());
        return safe;
    }

    private static final class SanitizedFailure extends RuntimeException {
        private SanitizedFailure(String message) { super(message); }
    }
}
