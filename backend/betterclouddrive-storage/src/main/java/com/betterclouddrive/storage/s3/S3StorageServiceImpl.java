package com.betterclouddrive.storage.s3;

import com.betterclouddrive.storage.StorageService;
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
            log.error("Failed to initialize storage bucket", e);
        }
    }

    @Override
    public String uploadObject(String objectKey, InputStream inputStream, long size, String contentType) {
        try {
            client.putObject(
                PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(objectKey)
                    .stream(inputStream, size, -1)
                    .contentType(contentType)
                    .build()
            );
            return objectKey;
        } catch (Exception e) {
            log.error("Failed to upload object: {}", objectKey, e);
            throw new RuntimeException("Storage upload failed: " + objectKey, e);
        }
    }

    @Override
    public InputStream downloadObject(String objectKey) {
        try {
            return client.getObject(
                GetObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(objectKey)
                    .build()
            );
        } catch (Exception e) {
            log.error("Failed to download object: {}", objectKey, e);
            throw new RuntimeException("Storage download failed: " + objectKey, e);
        }
    }

    @Override
    public InputStream downloadObjectRange(String objectKey, long offset, long length) {
        try {
            return client.getObject(
                GetObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(objectKey)
                    .offset(offset)
                    .length(length)
                    .build()
            );
        } catch (Exception e) {
            log.error("Failed to download object range: {} offset={} length={}", objectKey, offset, length, e);
            throw new RuntimeException("Storage range download failed: " + objectKey, e);
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        try {
            client.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(objectKey)
                    .build()
            );
        } catch (Exception e) {
            log.warn("Failed to delete object: {}", objectKey, e);
        }
    }

    @Override
    public String uploadPart(String prefix, int partNumber, InputStream data, long size) {
        String partKey = prefix + ".part." + (partNumber + 1);
        try {
            client.putObject(
                PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(partKey)
                    .stream(data, size, -1)
                    .build()
            );
            return partKey;
        } catch (Exception e) {
            log.error("Failed to upload part {} for prefix {}", partNumber, prefix, e);
            throw new RuntimeException("Part upload failed", e);
        }
    }

    @Override
    public void composeParts(String finalObjectKey, String sourcePrefix, int totalParts) {
        try {
            uploadComposedObject(finalObjectKey, sourcePrefix, totalParts);
            // Clean up parts after successful composition
            for (int i = 0; i < totalParts; i++) {
                deleteObject(partKey(sourcePrefix, i));
            }
        } catch (Exception e) {
            log.error("Failed to compose parts into: {}", finalObjectKey, e);
            throw new RuntimeException("Composition failed", e);
        }
    }

    private void uploadComposedObject(String finalObjectKey, String sourcePrefix, int totalParts) throws Exception {
        try (InputStream stream = new SequenceInputStream(partStreams(sourcePrefix, totalParts))) {
            client.putObject(
                PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(finalObjectKey)
                    .stream(stream, -1, COMPOSE_UPLOAD_PART_SIZE)
                    .build()
            );
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
                    return client.getObject(
                        GetObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(partKey)
                            .build()
                    );
                } catch (Exception e) {
                    throw new RuntimeException("Failed to read upload part: " + partKey, e);
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
            client.statObject(StatObjectArgs.builder().bucket(props.getBucket()).object(objectKey).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void copyObject(String sourceKey, String destKey) {
        try {
            client.copyObject(
                CopyObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(destKey)
                    .source(CopySource.builder().bucket(props.getBucket()).object(sourceKey).build())
                    .build()
            );
        } catch (Exception e) {
            log.error("Failed to copy object: {} -> {}", sourceKey, destKey, e);
            throw new RuntimeException("Storage copy failed", e);
        }
    }

    @Override
    public long getObjectSize(String objectKey) {
        try {
            return client.statObject(StatObjectArgs.builder()
                    .bucket(props.getBucket()).object(objectKey).build()).size();
        } catch (Exception e) {
            log.warn("Failed to get object size: {}", objectKey, e);
            return -1;
        }
    }
}
