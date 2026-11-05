package com.betterclouddrive.storage;

import java.io.InputStream;

public interface StorageService {
    /** Upload an object, returns the storage key */
    String uploadObject(String objectKey, InputStream inputStream, long size, String contentType);

    /** Download an object, returns the input stream */
    InputStream downloadObject(String objectKey);

    /** Download an object byte range, returns the input stream */
    InputStream downloadObjectRange(String objectKey, long offset, long length);

    /** Delete an object */
    void deleteObject(String objectKey);

    /** Upload a chunk/part for later composition. Returns the part object key. */
    String uploadPart(String prefix, int partNumber, InputStream data, long size);

    /** Compose parts and return their actual digest. The caller owns cleanup after commit. */
    ContentDigest composeParts(String finalObjectKey, String sourcePrefix, int totalParts);

    /** Read actual object bytes. Missing objects must be distinguished from storage failures. */
    default ContentDigest inspectObject(String objectKey) {
        try (InputStream input = downloadObject(objectKey)) {
            return ContentDigest.read(input);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to inspect storage content", e);
        }
    }

    /** Delete remaining parts (for cancelled/aborted uploads). */
    void deleteParts(String prefix, int maxParts);

    /** Check if an object exists */
    boolean objectExists(String objectKey);

    /** Copy an object (for instant upload dedup) */
    void copyObject(String sourceKey, String destKey);

    /** Get object metadata (size) */
    long getObjectSize(String objectKey);
}
