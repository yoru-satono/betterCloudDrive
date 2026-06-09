package com.betterclouddrive.storage;

import java.io.InputStream;

public interface StorageService {
    /** Upload an object, returns the storage key */
    String uploadObject(String objectKey, InputStream inputStream, long size, String contentType);

    /** Download an object, returns the input stream */
    InputStream downloadObject(String objectKey);

    /** Delete an object */
    void deleteObject(String objectKey);

    /** Upload a chunk/part for later composition. Returns the part object key. */
    String uploadPart(String prefix, int partNumber, InputStream data, long size);

    /** Compose all parts into the final object, then delete the parts. */
    void composeParts(String finalObjectKey, String sourcePrefix, int totalParts);

    /** Delete remaining parts (for cancelled/aborted uploads). */
    void deleteParts(String prefix, int maxParts);

    /** Check if an object exists */
    boolean objectExists(String objectKey);

    /** Copy an object (for instant upload dedup) */
    void copyObject(String sourceKey, String destKey);

    /** Get object metadata (size) */
    long getObjectSize(String objectKey);
}
