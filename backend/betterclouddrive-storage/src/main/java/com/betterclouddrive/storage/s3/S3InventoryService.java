package com.betterclouddrive.storage.s3;

import com.betterclouddrive.storage.StorageInventoryException;
import com.betterclouddrive.storage.StorageInventoryService;
import com.betterclouddrive.common.dto.StorageInventory.ObjectInfo;
import com.betterclouddrive.common.dto.StorageInventory.Page;
import com.betterclouddrive.storage.metrics.StorageMetrics;
import io.minio.ListObjectsV2Args;
import io.minio.MinioAsyncClient;
import io.minio.StatObjectArgs;
import io.minio.BucketExistsArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Own bounded-time client; never initializes buckets or changes the business client's timeouts. */
@Service
@RequiredArgsConstructor
public class S3InventoryService implements StorageInventoryService {
    private final S3StorageProperties properties;
    private final StorageMetrics metrics;
    private MinioAsyncClient client;
    private long nextHead;
    private long nextList;

    @PostConstruct
    public void init() {
        client = MinioAsyncClient.builder().endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey()).region(properties.getRegion())
                .httpClient(new okhttp3.OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false)
                        .readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
                        .callTimeout(15, TimeUnit.SECONDS).build()).build();
    }

    @Override
    public Page list(String continuationToken, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("invalid_inventory_page_size");
        try {
            verifyBucket();
            pace(false);
            return metrics.observe(StorageMetrics.Operation.LIST, () -> {
                var args = ListObjectsV2Args.builder().bucket(properties.getBucket()).delimiter("").prefix("")
                        .encodingType("url").maxKeys(limit);
                if (continuationToken != null && !continuationToken.isEmpty()) args.continuationToken(continuationToken);
                var result = client.listObjectsV2(args.build()).get().result();
                var objects = new ArrayList<ObjectInfo>(limit);
                if ((result.contents() == null && result.keyCount() > 0)
                        || (result.commonPrefixes() != null && !result.commonPrefixes().isEmpty())) {
                    throw new StorageInventoryException("inventory_response_invalid");
                }
                var keys = new java.util.HashSet<String>();
                for (var item : result.contents() == null ? java.util.List.<io.minio.messages.Contents>of() : result.contents()) {
                    item.setEncodingType(result.encodingType());
                    if (!keys.add(item.objectName())) throw new StorageInventoryException("inventory_response_invalid");
                    objects.add(new ObjectInfo(item.objectName(), item.size(), item.etag(), item.lastModified().toInstant()));
                }
                String next = result.nextContinuationToken();
                if (result.isTruncated() && (next == null || next.isEmpty() || next.equals(continuationToken))) {
                    throw new StorageInventoryException("inventory_cursor_invalid");
                }
                return new Page(objects, next, !result.isTruncated());
            });
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if (error instanceof StorageInventoryException unavailable) throw unavailable;
            throw new StorageInventoryException("inventory_list_unavailable");
        }
    }

    @Override
    public Optional<ObjectInfo> stat(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("invalid_inventory_key");
        try {
            pace(true);
            var object = metrics.observe(StorageMetrics.Operation.HEAD, () -> client.statObject(
                    StatObjectArgs.builder().bucket(properties.getBucket()).object(key).build()).get());
            return Optional.of(new ObjectInfo(key, object.size(), object.etag(), object.lastModified().toInstant()));
        } catch (Exception error) {
            Throwable cause = error instanceof java.util.concurrent.ExecutionException ? error.getCause() : error;
            if (cause instanceof ErrorResponseException remote
                    && Set.of("NoSuchKey", "NoSuchObject", "NotFound").contains(remote.errorResponse().code())) {
                verifyBucket();
                return Optional.empty();
            }
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new StorageInventoryException("inventory_head_unavailable");
        }
    }

    private void verifyBucket() {
        pace(true);
        try {
            metrics.observe(StorageMetrics.Operation.HEAD, () -> {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build()).get()) {
                    throw new StorageInventoryException("inventory_bucket_unavailable");
                }
                return true;
            });
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new StorageInventoryException("inventory_bucket_unavailable");
        }
    }

    private synchronized void pace(boolean head) {
        long next = head ? nextHead : nextList;
        long wait = next - System.nanoTime();
        if (wait > 0) {
            try { TimeUnit.NANOSECONDS.sleep(wait); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new StorageInventoryException("inspection_interrupted");
            }
        }
        long following = System.nanoTime() + (head ? 100_000_000L : 1_000_000_000L);
        if (head) nextHead = following; else nextList = following;
    }
}
