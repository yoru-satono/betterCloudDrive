package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.UploadSessionEntity;
import java.io.InputStream;
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

    UploadSessionEntity initTeamUpload(Long teamId, Long userId, Long parentId, String fileName, Long fileSize, String md5Hash, int totalChunks);

    void uploadTeamChunk(Long teamId, String sessionId, Long userId, int chunkNumber, byte[] data, String chunkMd5);

    Map<String, Object> getTeamUploadStatus(Long teamId, String sessionId, Long userId);

    Long completeTeamUpload(Long teamId, String sessionId, Long userId);

    void cancelTeamUpload(Long teamId, String sessionId, Long userId);

    Long instantTeamUpload(Long teamId, Long userId, Long parentId, String fileName, Long fileSize, String md5Hash);

    /**
     * Server-side streaming upload: auto-chunks the InputStream internally and runs
     * the full SeaweedFS multipart flow. Intended for callers (e.g. WebDAV PUT) that
     * cannot pre-split the file. No UploadSession is created; no Redis bitmap is used.
     *
     * @param md5Hash optional; computed on-the-fly when null
     * @return id of the created FileEntity
     */
    Long streamUpload(Long userId, Long parentId, String fileName,
                      Long fileSize, InputStream inputStream, String md5Hash);
}
