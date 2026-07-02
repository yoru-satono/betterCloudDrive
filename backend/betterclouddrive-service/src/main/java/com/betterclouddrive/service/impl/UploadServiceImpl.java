package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.UploadSessionEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.repository.UploadSessionRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamPermissionService;
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
    private final TeamRepository teamRepository;
    private final StorageService storageService;
    private final StringRedisTemplate redisTemplate;
    private final TeamAuditService teamAuditService;
    private final NotificationService notificationService;
    private final TeamPermissionService teamPermissionService;

    private static final String UPLOAD_BITMAP_PREFIX = "upload:bitmap:";
    private static final String STORAGE_INCR_PREFIX = "storage:incr:";
    private static final String TEAM_STORAGE_INCR_PREFIX = "team:storage:incr:";
    private static final int CHUNK_SIZE = 5242880;

    @Override
    @Transactional
    public UploadSessionEntity initUpload(Long userId, Long parentId, String fileName, Long fileSize, String md5Hash, int totalChunks) {
        validateUploadShape(fileSize, totalChunks);
        validatePersonalParent(userId, parentId);
        checkStorageQuota(userId, fileSize);

        String sessionId = UUID.randomUUID().toString();
        UploadSessionEntity session = UploadSessionEntity.builder()
                .id(sessionId)
                .userId(userId)
                .teamId(null)
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
        if (session == null || session.getTeamId() != null || !session.getUserId().equals(userId)) {
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
        if (session == null || session.getTeamId() != null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }
        validatePersonalParent(userId, session.getParentId());

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
        UploadSessionEntity session = uploadSessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null || session.getTeamId() != null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }
        if (Objects.equals(session.getStatus(), 2)) {
            return resolveCompletedFileId(session, userId, null);
        }
        if (!Objects.equals(session.getStatus(), 1)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Upload session is not active");
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
        if (session.getFileSize() != null && session.getFileSize() == 0L) {
            storageService.uploadObject(objectKey, new ByteArrayInputStream(new byte[0]), 0, guessMimeType(session.getFileName()));
        } else {
            storageService.composeParts(objectKey, chunkPrefix, session.getTotalChunks());
        }
        ensureObjectExists(objectKey);

        FileEntity file = buildFileEntity(userId, session.getParentId(), session.getFileName(),
                session.getFileSize(), session.getMd5Hash(), objectKey);
        file = fileRepository.save(file);

        session.setStatus(2);
        session.setStoragePath(objectKey);
        session.setCompletedFileId(file.getId());
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionRepository.save(session);

        redisTemplate.delete(bitmapKey);
        if (session.getFileSize() != null && session.getFileSize() > 0) {
            redisTemplate.opsForValue().increment("storage:incr:" + userId, session.getFileSize());
        }

        return file.getId();
    }

    @Override
    @Transactional
    public void cancelUpload(String sessionId, Long userId) {
        UploadSessionEntity session = uploadSessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getTeamId() != null || !session.getUserId().equals(userId)) {
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
        validatePersonalParent(userId, parentId);
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
    public UploadSessionEntity initTeamUpload(Long teamId, Long userId, Long parentId, String fileName, Long fileSize, String md5Hash, int totalChunks) {
        validateUploadShape(fileSize, totalChunks);
        validateTeamParent(teamId, parentId);
        checkTeamStorageQuota(teamId, fileSize);

        String sessionId = UUID.randomUUID().toString();
        UploadSessionEntity session = UploadSessionEntity.builder()
                .id(sessionId)
                .userId(userId)
                .teamId(teamId)
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
    public void uploadTeamChunk(Long teamId, String sessionId, Long userId, int chunkNumber, byte[] data, String chunkMd5) {
        UploadSessionEntity session = getActiveTeamSession(teamId, sessionId, userId);
        teamPermissionService.requireParentWrite(userId, teamId, session.getParentId());
        if (chunkNumber < 0 || chunkNumber >= session.getTotalChunks()) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Invalid chunk number");
        }

        String bitmapKey = UPLOAD_BITMAP_PREFIX + sessionId;
        Boolean alreadyUploaded = redisTemplate.opsForValue().getBit(bitmapKey, chunkNumber);
        if (Boolean.TRUE.equals(alreadyUploaded)) {
            return;
        }

        String chunkPrefix = teamChunkPrefix(teamId, sessionId);
        storageService.uploadPart(chunkPrefix, chunkNumber, new ByteArrayInputStream(data), data.length);

        redisTemplate.opsForValue().setBit(bitmapKey, chunkNumber, true);
        redisTemplate.expire(bitmapKey, 24, TimeUnit.HOURS);

        session.setReceivedChunks(session.getReceivedChunks() + 1);
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionRepository.save(session);
    }

    @Override
    public Map<String, Object> getTeamUploadStatus(Long teamId, String sessionId, Long userId) {
        UploadSessionEntity session = getTeamSession(teamId, sessionId, userId);
        teamPermissionService.requireParentWrite(userId, teamId, session.getParentId());
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
    public Long completeTeamUpload(Long teamId, String sessionId, Long userId) {
        UploadSessionEntity session = getTeamSessionForUpdate(teamId, sessionId, userId);
        teamPermissionService.requireParentWrite(userId, teamId, session.getParentId());
        if (Objects.equals(session.getStatus(), 2)) {
            return resolveCompletedFileId(session, userId, teamId);
        }
        if (!Objects.equals(session.getStatus(), 1)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Upload session is not active");
        }
        String bitmapKey = UPLOAD_BITMAP_PREFIX + sessionId;
        for (int i = 0; i < session.getTotalChunks(); i++) {
            Boolean bit = redisTemplate.opsForValue().getBit(bitmapKey, i);
            if (!Boolean.TRUE.equals(bit)) {
                throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Chunk " + i + " is missing");
            }
        }

        String objectKey = generateTeamStoragePath(teamId, session.getFileName());
        String chunkPrefix = teamChunkPrefix(teamId, sessionId);
        if (session.getFileSize() != null && session.getFileSize() == 0L) {
            storageService.uploadObject(objectKey, new ByteArrayInputStream(new byte[0]), 0, guessMimeType(session.getFileName()));
        } else {
            storageService.composeParts(objectKey, chunkPrefix, session.getTotalChunks());
        }
        ensureObjectExists(objectKey);

        FileEntity file = buildTeamFileEntity(teamId, session.getUserId(), session.getParentId(), session.getFileName(),
                session.getFileSize(), session.getMd5Hash(), objectKey);
        file = fileRepository.save(file);

        session.setStatus(2);
        session.setStoragePath(objectKey);
        session.setCompletedFileId(file.getId());
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionRepository.save(session);

        redisTemplate.delete(bitmapKey);
        if (session.getFileSize() != null && session.getFileSize() > 0) {
            redisTemplate.opsForValue().increment(TEAM_STORAGE_INCR_PREFIX + teamId, session.getFileSize());
        }
        notificationService.checkTeamQuota(teamId);
        teamAuditService.record(teamId, userId, "UPLOAD_COMPLETE", "FILE", file.getId(), file.getFileName());
        return file.getId();
    }

    @Override
    @Transactional
    public void cancelTeamUpload(Long teamId, String sessionId, Long userId) {
        UploadSessionEntity session = getTeamSession(teamId, sessionId, userId);
        teamPermissionService.requireParentWrite(userId, teamId, session.getParentId());
        storageService.deleteParts(teamChunkPrefix(teamId, sessionId), session.getTotalChunks());
        redisTemplate.delete(UPLOAD_BITMAP_PREFIX + sessionId);
        session.setStatus(3);
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionRepository.save(session);
        teamAuditService.record(teamId, userId, "UPLOAD_CANCEL", "UPLOAD", null, sessionId);
    }

    @Override
    @Transactional
    public Long instantTeamUpload(Long teamId, Long userId, Long parentId, String fileName, Long fileSize, String md5Hash) {
        validateTeamParent(teamId, parentId);
        checkTeamStorageQuota(teamId, fileSize);

        FileEntity existing = fileRepository.findFirstByMd5HashAndIsDeletedFalseOrderByCreatedAtDesc(md5Hash).orElse(null);
        if (existing == null) {
            throw new BusinessException(ApiCode.INSTANT_UPLOAD_NOT_FOUND);
        }

        FileEntity file = buildTeamFileEntity(teamId, userId, parentId, fileName, fileSize, md5Hash, existing.getStoragePath());
        fileRepository.save(file);

        redisTemplate.opsForValue().increment(TEAM_STORAGE_INCR_PREFIX + teamId, fileSize);
        notificationService.checkTeamQuota(teamId);
        teamAuditService.record(teamId, userId, "UPLOAD_INSTANT", "FILE", file.getId(), file.getFileName());
        return file.getId();
    }

    @Override
    @Transactional
    public Long streamUpload(Long userId, Long parentId, String fileName,
                             Long fileSize, InputStream inputStream, String md5Hash) {
        validatePersonalParent(userId, parentId);
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
        if (fileSize == 0L) {
            storageService.uploadObject(objectKey, new ByteArrayInputStream(new byte[0]), 0, guessMimeType(fileName));
        } else {
            storageService.composeParts(objectKey, chunkPrefix, totalChunks);
        }

        FileEntity file = buildFileEntity(userId, parentId, fileName, fileSize, md5Hash, objectKey);
        fileRepository.save(file);

        if (fileSize > 0) {
            redisTemplate.opsForValue().increment(STORAGE_INCR_PREFIX + userId, fileSize);
        }
        return file.getId();
    }

    private void validateUploadShape(Long fileSize, int totalChunks) {
        if (fileSize == null || fileSize < 0 || totalChunks < 0) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Invalid upload size or chunk count");
        }
        if (fileSize == 0 && totalChunks != 0) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Zero-byte uploads must not include chunks");
        }
        if (fileSize > 0 && totalChunks <= 0) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Chunk count is required for non-empty uploads");
        }
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
                .teamId(null)
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

    private FileEntity buildTeamFileEntity(Long teamId, Long userId, Long parentId, String fileName,
                                           Long fileSize, String md5Hash, String storagePath) {
        return FileEntity.builder()
                .userId(userId)
                .teamId(teamId)
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
        long storageUsed = user.getStorageUsed() != null ? user.getStorageUsed() : 0L;
        long storageQuota = user.getStorageQuota() != null ? user.getStorageQuota() : 0L;
        long currentUsed = storageUsed + pendingIncr;
        if (currentUsed + fileSize > storageQuota) {
            throw new BusinessException(ApiCode.STORAGE_QUOTA_EXCEEDED,
                    "Storage quota exceeded. Used: " + currentUsed + ", Quota: " + storageQuota);
        }
    }

    private void checkTeamStorageQuota(Long teamId, Long fileSize) {
        TeamEntity team = teamRepository.findByIdAndDeletedAtIsNull(teamId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Team not found"));
        String incrStr = redisTemplate.opsForValue().get(TEAM_STORAGE_INCR_PREFIX + teamId);
        long pendingIncr = 0;
        if (incrStr != null) {
            try {
                pendingIncr = Long.parseLong(incrStr);
            } catch (NumberFormatException ignored) {
            }
        }
        long storageUsed = team.getStorageUsed() != null ? team.getStorageUsed() : 0L;
        long storageQuota = team.getStorageQuota() != null ? team.getStorageQuota() : 0L;
        long currentUsed = storageUsed + pendingIncr;
        if (currentUsed + fileSize > storageQuota) {
            notificationService.notifyTeamQuotaExceeded(teamId);
            throw new BusinessException(ApiCode.STORAGE_QUOTA_EXCEEDED,
                    "Team storage quota exceeded. Used: " + currentUsed + ", Quota: " + storageQuota);
        }
    }

    private UploadSessionEntity getTeamSession(Long teamId, String sessionId, Long userId) {
        UploadSessionEntity session = uploadSessionRepository.findById(sessionId).orElse(null);
        if (session == null
                || !Objects.equals(session.getTeamId(), teamId)
                || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }
        return session;
    }

    private UploadSessionEntity getTeamSessionForUpdate(Long teamId, String sessionId, Long userId) {
        UploadSessionEntity session = uploadSessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null
                || !Objects.equals(session.getTeamId(), teamId)
                || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }
        return session;
    }

    private UploadSessionEntity getActiveTeamSession(Long teamId, String sessionId, Long userId) {
        UploadSessionEntity session = getTeamSession(teamId, sessionId, userId);
        if (session.getStatus() != 1) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Upload session is not active");
        }
        return session;
    }

    private Long resolveCompletedFileId(UploadSessionEntity session, Long userId, Long teamId) {
        FileEntity file = null;
        if (session.getCompletedFileId() != null) {
            file = fileRepository.findById(session.getCompletedFileId()).orElse(null);
        }
        if (file == null && session.getStoragePath() != null && !session.getStoragePath().isBlank()) {
            file = fileRepository.findFirstByStoragePathAndIsDeletedFalseOrderByIdDesc(session.getStoragePath())
                    .orElse(null);
            if (file != null && session.getCompletedFileId() == null) {
                session.setCompletedFileId(file.getId());
                session.setUpdatedAt(LocalDateTime.now());
                uploadSessionRepository.save(session);
            }
        }
        if (!isCompletedUploadFile(file, userId, teamId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Completed upload result is missing");
        }
        ensureObjectExists(file.getStoragePath());
        return file.getId();
    }

    private boolean isCompletedUploadFile(FileEntity file, Long userId, Long teamId) {
        if (file == null || Boolean.TRUE.equals(file.getIsDeleted())) {
            return false;
        }
        if (teamId == null) {
            return Objects.equals(file.getUserId(), userId) && file.getTeamId() == null;
        }
        return Objects.equals(file.getUserId(), userId) && Objects.equals(file.getTeamId(), teamId);
    }

    private void ensureObjectExists(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || !storageService.objectExists(objectKey)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Completed upload object is missing");
        }
    }

    private void validateTeamParent(Long teamId, Long parentId) {
        if (parentId == null) {
            return;
        }
        FileEntity parent = fileRepository.findById(parentId).orElse(null);
        if (parent == null
                || parent.getIsDeleted()
                || !Objects.equals(parent.getTeamId(), teamId)
                || !"folder".equals(parent.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
    }

    private void validatePersonalParent(Long userId, Long parentId) {
        if (parentId == null) {
            return;
        }
        FileEntity parent = fileRepository.findById(parentId).orElse(null);
        if (parent == null
                || parent.getIsDeleted()
                || parent.getTeamId() != null
                || !Objects.equals(parent.getUserId(), userId)
                || !"folder".equals(parent.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
    }

    private String teamChunkPrefix(Long teamId, String sessionId) {
        return "uploads/teams/" + teamId + "/" + sessionId + "/chunks";
    }

    private String generateTeamStoragePath(Long teamId, String fileName) {
        LocalDateTime now = LocalDateTime.now();
        String randomId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "teams/" + teamId + "/" + now.getYear() + "/"
                + String.format("%02d", now.getMonthValue()) + "/"
                + randomId + "_" + fileName;
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
