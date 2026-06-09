package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.UploadSessionEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.mapper.FileMapper;
import com.betterclouddrive.dal.mapper.UploadSessionMapper;
import com.betterclouddrive.dal.mapper.UserMapper;
import com.betterclouddrive.service.UploadService;
import com.betterclouddrive.storage.StorageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    private final UploadSessionMapper uploadSessionMapper;
    private final FileMapper fileMapper;
    private final UserMapper userMapper;
    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;

    private static final String UPLOAD_BITMAP_PREFIX = "upload:bitmap:";
    private static final String STORAGE_INCR_PREFIX = "storage:incr:";
    private static final int CHUNK_SIZE = 5242880; // 5MB

    @Override
    @Transactional
    public UploadSessionEntity initUpload(Long userId, Long parentId, String fileName, Long fileSize, String md5Hash, int totalChunks) {
        // Check storage quota before allowing upload
        checkStorageQuota(userId, fileSize);

        String sessionId = UUID.randomUUID().toString();

        UploadSessionEntity session = UploadSessionEntity.builder()
                .id(sessionId)
                .userId(userId)
                .parentId(parentId)
                .fileName(fileName)
                .fileSize(fileSize)
                .md5Hash(md5Hash)
                .chunkSize(CHUNK_SIZE)
                .totalChunks(totalChunks)
                .receivedChunks(0)
                .status(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        uploadSessionMapper.insert(session);

        return session;
    }

    @Override
    @Transactional
    public void uploadChunk(String sessionId, Long userId, int chunkNumber, byte[] data, String chunkMd5) {
        UploadSessionEntity session = uploadSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }
        if (session.getStatus() != 1) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Upload session is not active");
        }
        if (chunkNumber < 0 || chunkNumber >= session.getTotalChunks()) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Invalid chunk number");
        }

        // Check if chunk already uploaded via Redis bitmap
        String bitmapKey = UPLOAD_BITMAP_PREFIX + sessionId;
        Boolean alreadyUploaded = redisTemplate.opsForValue().getBit(bitmapKey, chunkNumber);

        if (Boolean.TRUE.equals(alreadyUploaded)) {
            return; // chunk already uploaded, skip
        }

        // Upload chunk part to storage
        String chunkPrefix = "uploads/" + userId + "/" + sessionId + "/chunks";
        storageService.uploadPart(chunkPrefix, chunkNumber, new ByteArrayInputStream(data), data.length);

        // Mark chunk as uploaded in Redis bitmap
        redisTemplate.opsForValue().setBit(bitmapKey, chunkNumber, true);
        redisTemplate.expire(bitmapKey, 24, TimeUnit.HOURS);

        // Update received count
        session.setReceivedChunks(session.getReceivedChunks() + 1);
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionMapper.updateById(session);
    }

    @Override
    public Map<String, Object> getUploadStatus(String sessionId, Long userId) {
        UploadSessionEntity session = uploadSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }

        String bitmapKey = UPLOAD_BITMAP_PREFIX + sessionId;
        List<Integer> missingChunks = new ArrayList<>();
        for (int i = 0; i < session.getTotalChunks(); i++) {
            Boolean bit = redisTemplate.opsForValue().getBit(bitmapKey, i);
            if (!Boolean.TRUE.equals(bit)) {
                missingChunks.add(i);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("totalChunks", session.getTotalChunks());
        result.put("uploadedChunks", session.getReceivedChunks());
        result.put("missingChunks", missingChunks);
        return result;
    }

    @Override
    @Transactional
    public Long completeUpload(String sessionId, Long userId) {
        UploadSessionEntity session = uploadSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }

        // Verify all chunks uploaded via Redis bitmap
        String bitmapKey = UPLOAD_BITMAP_PREFIX + sessionId;
        for (int i = 0; i < session.getTotalChunks(); i++) {
            Boolean bit = redisTemplate.opsForValue().getBit(bitmapKey, i);
            if (!Boolean.TRUE.equals(bit)) {
                throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Chunk " + i + " is missing");
            }
        }

        // Compose all chunk parts into the final object
        String objectKey = userId + "/" + LocalDateTime.now().getYear() + "/"
                + String.format("%02d", LocalDateTime.now().getMonthValue()) + "/"
                + UUID.randomUUID().toString().substring(0, 8)
                + "_" + session.getFileName();
        String chunkPrefix = "uploads/" + userId + "/" + sessionId + "/chunks";
        storageService.composeParts(objectKey, chunkPrefix, session.getTotalChunks());

        // Create file record
        String mimeType = guessMimeType(session.getFileName());
        FileEntity file = FileEntity.builder()
                .userId(userId)
                .parentId(session.getParentId())
                .fileName(session.getFileName())
                .fileType("file")
                .mimeType(mimeType)
                .fileSize(session.getFileSize())
                .storagePath(objectKey)
                .md5Hash(session.getMd5Hash())
                .isDeleted(false)
                .versionCount(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        fileMapper.insert(file);

        // Update session status
        session.setStatus(2);
        session.setStoragePath(objectKey);
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionMapper.updateById(session);

        // Cleanup Redis bitmap
        redisTemplate.delete(bitmapKey);

        // Increment storage used in Redis
        redisTemplate.opsForValue().increment("storage:incr:" + userId, session.getFileSize());

        return file.getId();
    }

    @Override
    @Transactional
    public void cancelUpload(String sessionId, Long userId) {
        UploadSessionEntity session = uploadSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }

        // Delete uploaded chunk parts from storage
        String chunkPrefix = "uploads/" + userId + "/" + sessionId + "/chunks";
        storageService.deleteParts(chunkPrefix, session.getTotalChunks());

        // Cleanup Redis bitmap
        redisTemplate.delete(UPLOAD_BITMAP_PREFIX + sessionId);

        // Mark session as cancelled
        session.setStatus(3);
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionMapper.updateById(session);
    }

    @Override
    @Transactional
    public Long instantUpload(Long userId, Long parentId, String fileName, Long fileSize, String md5Hash) {
        // Check storage quota before allowing upload
        checkStorageQuota(userId, fileSize);

        // Look for an existing file with the same MD5
        FileEntity existing = fileMapper.selectOne(
                new LambdaQueryWrapper<FileEntity>()
                        .eq(FileEntity::getMd5Hash, md5Hash)
                        .eq(FileEntity::getIsDeleted, false)
                        .orderByDesc(FileEntity::getCreatedAt)
                        .last("LIMIT 1")
        );

        if (existing == null) {
            throw new BusinessException(ApiCode.INSTANT_UPLOAD_NOT_FOUND);
        }

        // Create a new file record pointing to the same storage path
        String mimeType = guessMimeType(fileName);
        FileEntity file = FileEntity.builder()
                .userId(userId)
                .parentId(parentId)
                .fileName(fileName)
                .fileType("file")
                .mimeType(mimeType)
                .fileSize(fileSize)
                .storagePath(existing.getStoragePath())
                .md5Hash(md5Hash)
                .isDeleted(false)
                .versionCount(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        fileMapper.insert(file);

        // Increment storage used in Redis
        redisTemplate.opsForValue().increment("storage:incr:" + userId, fileSize);

        return file.getId();
    }

    private void checkStorageQuota(Long userId, Long fileSize) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ApiCode.UNAUTHORIZED);
        }
        // Include pending Redis increments not yet synced to DB
        String incrStr = redisTemplate.opsForValue().get(STORAGE_INCR_PREFIX + userId);
        long pendingIncr = 0;
        if (incrStr != null) {
            try {
                pendingIncr = Long.parseLong(incrStr);
            } catch (NumberFormatException ignored) {
            }
        }
        long currentUsed = user.getStorageUsed() + pendingIncr;
        if (currentUsed + fileSize > user.getStorageQuota()) {
            throw new BusinessException(ApiCode.STORAGE_QUOTA_EXCEEDED,
                    "Storage quota exceeded. Used: " + currentUsed + ", Quota: " + user.getStorageQuota());
        }
    }

    private String guessMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String ext = fileName.toLowerCase();
        if (ext.endsWith(".jpg") || ext.endsWith(".jpeg")) return "image/jpeg";
        if (ext.endsWith(".png")) return "image/png";
        if (ext.endsWith(".gif")) return "image/gif";
        if (ext.endsWith(".webp")) return "image/webp";
        if (ext.endsWith(".pdf")) return "application/pdf";
        if (ext.endsWith(".txt")) return "text/plain";
        if (ext.endsWith(".md")) return "text/markdown";
        if (ext.endsWith(".json")) return "application/json";
        if (ext.endsWith(".xml")) return "application/xml";
        if (ext.endsWith(".zip")) return "application/zip";
        if (ext.endsWith(".mp4")) return "video/mp4";
        if (ext.endsWith(".mp3")) return "audio/mpeg";
        if (ext.endsWith(".doc") || ext.endsWith(".docx")) return "application/msword";
        return "application/octet-stream";
    }
}
