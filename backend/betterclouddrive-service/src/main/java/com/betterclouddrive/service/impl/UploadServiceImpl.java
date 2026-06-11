package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.UploadSessionEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.UploadSessionRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.UploadService;
import com.betterclouddrive.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    private final UploadSessionRepository uploadSessionRepository;
    private final FileRepository fileRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;

    private static final String UPLOAD_BITMAP_PREFIX = "upload:bitmap:";
    private static final String STORAGE_INCR_PREFIX = "storage:incr:";
    private static final int CHUNK_SIZE = 5242880;

    @Override
    @Transactional
    public UploadSessionEntity initUpload(Long userId, Long parentId, String fileName, Long fileSize, String md5Hash, int totalChunks) {
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
        return uploadSessionRepository.save(session);
    }

    @Override
    @Transactional
    public void uploadChunk(String sessionId, Long userId, int chunkNumber, byte[] data, String chunkMd5) {
        UploadSessionEntity session = uploadSessionRepository.findById(sessionId).orElse(null);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }
        if (session.getStatus() != 1) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Upload session is not active");
        }
        if (chunkNumber < 0 || chunkNumber >= session.getTotalChunks()) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Invalid chunk number");
        }

        String bitmapKey = UPLOAD_BITMAP_PREFIX + sessionId;
        Boolean alreadyUploaded = redisTemplate.opsForValue().getBit(bitmapKey, chunkNumber);
        if (Boolean.TRUE.equals(alreadyUploaded)) {
            return;
        }

        String chunkPrefix = "uploads/" + userId + "/" + sessionId + "/chunks";
        storageService.uploadPart(chunkPrefix, chunkNumber, new ByteArrayInputStream(data), data.length);

        redisTemplate.opsForValue().setBit(bitmapKey, chunkNumber, true);
        redisTemplate.expire(bitmapKey, 24, TimeUnit.HOURS);

        session.setReceivedChunks(session.getReceivedChunks() + 1);
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionRepository.save(session);
    }

    @Override
    public Map<String, Object> getUploadStatus(String sessionId, Long userId) {
        UploadSessionEntity session = uploadSessionRepository.findById(sessionId).orElse(null);
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
        UploadSessionEntity session = uploadSessionRepository.findById(sessionId).orElse(null);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }

        String bitmapKey = UPLOAD_BITMAP_PREFIX + sessionId;
        for (int i = 0; i < session.getTotalChunks(); i++) {
            Boolean bit = redisTemplate.opsForValue().getBit(bitmapKey, i);
            if (!Boolean.TRUE.equals(bit)) {
                throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Chunk " + i + " is missing");
            }
        }

        String objectKey = generateStoragePath(userId, session.getFileName());
        String chunkPrefix = "uploads/" + userId + "/" + sessionId + "/chunks";
        storageService.composeParts(objectKey, chunkPrefix, session.getTotalChunks());

        FileEntity file = buildFileEntity(userId, session.getParentId(), session.getFileName(),
                session.getFileSize(), session.getMd5Hash(), objectKey);
        fileRepository.save(file);

        session.setStatus(2);
        session.setStoragePath(objectKey);
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionRepository.save(session);

        redisTemplate.delete(bitmapKey);
        redisTemplate.opsForValue().increment("storage:incr:" + userId, session.getFileSize());

        return file.getId();
    }

    @Override
    @Transactional
    public void cancelUpload(String sessionId, Long userId) {
        UploadSessionEntity session = uploadSessionRepository.findById(sessionId).orElse(null);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }

        String chunkPrefix = "uploads/" + userId + "/" + sessionId + "/chunks";
        storageService.deleteParts(chunkPrefix, session.getTotalChunks());

        redisTemplate.delete(UPLOAD_BITMAP_PREFIX + sessionId);

        session.setStatus(3);
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionRepository.save(session);
    }

    @Override
    @Transactional
    public Long instantUpload(Long userId, Long parentId, String fileName, Long fileSize, String md5Hash) {
        checkStorageQuota(userId, fileSize);

        FileEntity existing = fileRepository.findFirstByMd5HashAndIsDeletedFalseOrderByCreatedAtDesc(md5Hash).orElse(null);
        if (existing == null) {
            throw new BusinessException(ApiCode.INSTANT_UPLOAD_NOT_FOUND);
        }

        FileEntity file = buildFileEntity(userId, parentId, fileName, fileSize, md5Hash, existing.getStoragePath());
        fileRepository.save(file);

        redisTemplate.opsForValue().increment("storage:incr:" + userId, fileSize);
        return file.getId();
    }

    @Override
    @Transactional
    public Long streamUpload(Long userId, Long parentId, String fileName,
                             Long fileSize, InputStream inputStream, String md5Hash) {
        checkStorageQuota(userId, fileSize);

        int totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
        String sessionId = UUID.randomUUID().toString();
        String chunkPrefix = "uploads/" + userId + "/" + sessionId + "/chunks";

        MessageDigest digest = null;
        if (md5Hash == null) {
            try {
                digest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("MD5 not available", e);
            }
        }

        try {
            byte[] buffer = new byte[CHUNK_SIZE];
            int chunkIndex = 0;
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byte[] chunk = bytesRead == buffer.length ? buffer : Arrays.copyOf(buffer, bytesRead);
                if (digest != null) {
                    digest.update(chunk, 0, bytesRead);
                }
                storageService.uploadPart(chunkPrefix, chunkIndex, new ByteArrayInputStream(chunk), bytesRead);
                chunkIndex++;
            }
        } catch (IOException e) {
            storageService.deleteParts(chunkPrefix, totalChunks);
            throw new RuntimeException("Failed to read upload stream", e);
        }

        if (digest != null) {
            md5Hash = HexFormat.of().formatHex(digest.digest());
        }

        String objectKey = generateStoragePath(userId, fileName);
        storageService.composeParts(objectKey, chunkPrefix, totalChunks);

        FileEntity file = buildFileEntity(userId, parentId, fileName, fileSize, md5Hash, objectKey);
        fileRepository.save(file);

        redisTemplate.opsForValue().increment(STORAGE_INCR_PREFIX + userId, fileSize);
        return file.getId();
    }

    private String generateStoragePath(Long userId, String fileName) {
        LocalDateTime now = LocalDateTime.now();
        String randomId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return userId + "/" + now.getYear() + "/"
                + String.format("%02d", now.getMonthValue()) + "/"
                + randomId + "_" + fileName;
    }

    private FileEntity buildFileEntity(Long userId, Long parentId, String fileName,
                                       Long fileSize, String md5Hash, String storagePath) {
        return FileEntity.builder()
                .userId(userId)
                .parentId(parentId)
                .fileName(fileName)
                .fileType("file")
                .mimeType(guessMimeType(fileName))
                .fileSize(fileSize)
                .storagePath(storagePath)
                .md5Hash(md5Hash)
                .isDeleted(false)
                .versionCount(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private void checkStorageQuota(Long userId, Long fileSize) {
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw new BusinessException(ApiCode.UNAUTHORIZED);
        }
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
