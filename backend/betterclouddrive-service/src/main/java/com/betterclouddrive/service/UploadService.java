package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.UploadSessionEntity;
import java.util.Map;

public interface UploadService {
    /** Initialize a chunked upload session */
    UploadSessionEntity initUpload(Long userId, Long parentId, String fileName, Long fileSize, String md5Hash, int totalChunks);

    /** Upload a single chunk */
    void uploadChunk(String sessionId, Long userId, int chunkNumber, byte[] data, String chunkMd5);

    /** Get upload progress (uploaded chunk indices) */
    Map<String, Object> getUploadStatus(String sessionId, Long userId);

    /** Complete the upload: merge chunks and create file record */
    Long completeUpload(String sessionId, Long userId);

    /** Cancel an upload session */
    void cancelUpload(String sessionId, Long userId);

    /** Instant upload: if MD5 matches an existing file, link it directly */
    Long instantUpload(Long userId, Long parentId, String fileName, Long fileSize, String md5Hash);
}
