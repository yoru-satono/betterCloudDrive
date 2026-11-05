package com.betterclouddrive.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Result of starting an upload: either a completed file or a resumable session. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UploadInitResult(Mode mode, Long fileId, String sessionId, Integer chunkSize, Integer totalChunks) {
    public enum Mode { INSTANT, MULTIPART }

    public UploadInitResult {
        boolean instant = mode == Mode.INSTANT && fileId != null && fileId > 0
                && sessionId == null && chunkSize == null && totalChunks == null;
        boolean multipart = mode == Mode.MULTIPART && fileId == null && sessionId != null && !sessionId.isBlank()
                && chunkSize != null && chunkSize > 0 && totalChunks != null && totalChunks >= 0;
        if (!instant && !multipart) throw new IllegalArgumentException("Invalid upload initialization result");
    }

    public static UploadInitResult instant(Long fileId) {
        return new UploadInitResult(Mode.INSTANT, fileId, null, null, null);
    }

    public static UploadInitResult multipart(String sessionId, int chunkSize, int totalChunks) {
        return new UploadInitResult(Mode.MULTIPART, null, sessionId, chunkSize, totalChunks);
    }
}
