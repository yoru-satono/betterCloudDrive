package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.UploadInitResult;
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
import com.betterclouddrive.service.ThumbnailService;
import com.betterclouddrive.service.UploadService;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.storage.ContentDigest;
import com.betterclouddrive.storage.ObjectNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final ThumbnailService thumbnailService;

    private static final String UPLOAD_BITMAP_PREFIX = "upload:bitmap:";
    private static final String STORAGE_INCR_PREFIX = "storage:incr:";
    private static final String TEAM_STORAGE_INCR_PREFIX = "team:storage:incr:";
    private static final int CHUNK_SIZE = 5242880;
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final String INVALID_FILE_NAME = "error.invalidFileName";

    @Override
    @Transactional
    public UploadInitResult initUpload(Long userId, Long parentId, String fileName, Long fileSize, String md5Hash) {
        int chunks = uploadChunkCount(fileSize);
        md5Hash = normalizeMd5(md5Hash);
        fileName = normalizeFileName(fileName);
        validatePersonalParent(userId, parentId);
        checkStorageQuota(userId, fileSize);
        Optional<FileEntity> candidate = verifiedInstantCandidate(fileSize, md5Hash);
        if (candidate.isPresent()) {
            FileEntity file = fileRepository.save(buildFileEntity(userId, parentId, fileName, fileSize, md5Hash, candidate.get().getStoragePath()));
            scheduleThumbnailAfterCommit(file);
            incrementAfterCommit(STORAGE_INCR_PREFIX + userId, fileSize);
            return UploadInitResult.instant(file.getId());
        }
        return createUploadSession(null, userId, parentId, fileName, fileSize, md5Hash, chunks);
    }

    @Override
    @Transactional
    public void uploadChunk(String sessionId, Long userId, int chunkNumber, byte[] data, String chunkMd5) {
        UploadSessionEntity session = uploadSessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null || session.getTeamId() != null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }
        if (session.getStatus() != 1) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Upload session is not active");
        }
        validatePersonalParent(userId, session.getParentId());
        receiveChunk(session, "uploads/" + userId + "/" + sessionId + "/chunks", chunkNumber, data, chunkMd5);
    }

    @Override
    public Map<String, Object> getUploadStatus(String sessionId, Long userId) {
        UploadSessionEntity session = uploadSessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getTeamId() != null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }
        validatePersonalParent(userId, session.getParentId());

        validateSession(session);
        if (Objects.equals(session.getStatus(), 3)) throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);

        String bitmapKey = UPLOAD_BITMAP_PREFIX + sessionId;
        List<Integer> missingChunks = new ArrayList<>();
        for (int i = 0; !Objects.equals(session.getStatus(), 2) && i < session.getTotalChunks(); i++) {
            Boolean bit = redisTemplate.opsForValue().getBit(bitmapKey, i);
            if (!Boolean.TRUE.equals(bit)) {
                missingChunks.add(i);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("totalChunks", session.getTotalChunks());
        result.put("uploadedChunks", session.getTotalChunks() - missingChunks.size());
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
        validateSession(session);
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
        ContentDigest actual = composeVerified(objectKey, chunkPrefix, session.getTotalChunks(),
                session.getFileSize(), session.getMd5Hash(), session.getFileName());

        FileEntity file = buildFileEntity(userId, session.getParentId(), session.getFileName(),
                actual.size(), actual.md5(), objectKey);
        file = fileRepository.save(file);
        scheduleThumbnailAfterCommit(file);

        session.setStatus(2);
        session.setStoragePath(objectKey);
        session.setCompletedFileId(file.getId());
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionRepository.save(session);

        cleanupAfterCommit(chunkPrefix, session.getTotalChunks(), bitmapKey);
        if (session.getFileSize() != null && session.getFileSize() > 0) {
            incrementAfterCommit(STORAGE_INCR_PREFIX + userId, session.getFileSize());
        }

        return file.getId();
    }

    @Override
    @Transactional
    public void cancelUpload(String sessionId, Long userId) {
        UploadSessionEntity session = uploadSessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null || session.getTeamId() != null || !session.getUserId().equals(userId)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }

        if (!Objects.equals(session.getStatus(), 1)) return;
        String chunkPrefix = "uploads/" + userId + "/" + sessionId + "/chunks";
        storageService.deleteParts(chunkPrefix, session.getTotalChunks());

        redisTemplate.delete(UPLOAD_BITMAP_PREFIX + sessionId);

        session.setStatus(3);
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionRepository.save(session);
    }

    @Override
    @Transactional
    public UploadInitResult initTeamUpload(Long teamId, Long userId, Long parentId, String fileName, Long fileSize, String md5Hash) {
        lockTeamMutations(teamId);
        teamPermissionService.requireParentWrite(userId, teamId, parentId);
        fileName = normalizeFileName(fileName);
        int chunks = uploadChunkCount(fileSize);
        md5Hash = normalizeMd5(md5Hash);
        validateTeamParent(teamId, parentId);
        requireTeamNameAvailable(teamId, parentId, fileName);
        checkTeamStorageQuota(teamId, fileSize);
        Optional<FileEntity> candidate = verifiedInstantCandidate(fileSize, md5Hash);
        if (candidate.isPresent()) {
            FileEntity file = fileRepository.save(buildTeamFileEntity(teamId, userId, parentId, fileName, fileSize, md5Hash, candidate.get().getStoragePath()));
            scheduleThumbnailAfterCommit(file);
            incrementAfterCommit(TEAM_STORAGE_INCR_PREFIX + teamId, fileSize);
            notificationService.checkTeamQuota(teamId);
            teamAuditService.record(teamId, userId, "UPLOAD_INSTANT", "FILE", file.getId(), file.getFileName());
            return UploadInitResult.instant(file.getId());
        }
        return createUploadSession(teamId, userId, parentId, fileName, fileSize, md5Hash, chunks);
    }

    private UploadInitResult createUploadSession(Long teamId, Long userId, Long parentId, String name, long size, String md5, int chunks) {
        UploadSessionEntity session = UploadSessionEntity.builder().id(UUID.randomUUID().toString())
                .userId(userId).teamId(teamId).parentId(parentId).fileName(name).fileSize(size).md5Hash(md5)
                .chunkSize(CHUNK_SIZE).totalChunks(chunks).receivedChunks(0).status(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        uploadSessionRepository.save(session);
        return UploadInitResult.multipart(session.getId(), CHUNK_SIZE, chunks);
    }

    @Override
    @Transactional
    public void uploadTeamChunk(Long teamId, String sessionId, Long userId, int chunkNumber, byte[] data, String chunkMd5) {
        UploadSessionEntity session = getTeamSessionForUpdate(teamId, sessionId, userId);
        lockTeamMutations(teamId);
        if (!Objects.equals(session.getStatus(), 1)) throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        teamPermissionService.requireParentWrite(userId, teamId, session.getParentId());
        receiveChunk(session, teamChunkPrefix(teamId, sessionId), chunkNumber, data, chunkMd5);
    }

    @Override
    public Map<String, Object> getTeamUploadStatus(Long teamId, String sessionId, Long userId) {
        UploadSessionEntity session = getTeamSession(teamId, sessionId, userId);
        teamPermissionService.requireParentWrite(userId, teamId, session.getParentId());
        validateSession(session);
        if (Objects.equals(session.getStatus(), 3)) throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);

        String bitmapKey = UPLOAD_BITMAP_PREFIX + sessionId;
        List<Integer> missingChunks = new ArrayList<>();
        for (int i = 0; !Objects.equals(session.getStatus(), 2) && i < session.getTotalChunks(); i++) {
            Boolean bit = redisTemplate.opsForValue().getBit(bitmapKey, i);
            if (!Boolean.TRUE.equals(bit)) {
                missingChunks.add(i);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("totalChunks", session.getTotalChunks());
        result.put("uploadedChunks", session.getTotalChunks() - missingChunks.size());
        result.put("missingChunks", missingChunks);
        return result;
    }

    @Override
    @Transactional
    public Long completeTeamUpload(Long teamId, String sessionId, Long userId) {
        UploadSessionEntity session = getTeamSessionForUpdate(teamId, sessionId, userId);
        lockTeamMutations(teamId);
        teamPermissionService.requireParentWrite(userId, teamId, session.getParentId());
        validateSession(session);
        if (Objects.equals(session.getStatus(), 2)) {
            return resolveCompletedFileId(session, userId, teamId);
        }
        if (!Objects.equals(session.getStatus(), 1)) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Upload session is not active");
        }
        String normalizedFileName = normalizeFileName(session.getFileName());
        requireTeamNameAvailable(teamId, session.getParentId(), normalizedFileName);
        session.setFileName(normalizedFileName);
        String bitmapKey = UPLOAD_BITMAP_PREFIX + sessionId;
        for (int i = 0; i < session.getTotalChunks(); i++) {
            Boolean bit = redisTemplate.opsForValue().getBit(bitmapKey, i);
            if (!Boolean.TRUE.equals(bit)) {
                throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID, "Chunk " + i + " is missing");
            }
        }

        String objectKey = generateTeamStoragePath(teamId, session.getFileName());
        String chunkPrefix = teamChunkPrefix(teamId, sessionId);
        ContentDigest actual = composeVerified(objectKey, chunkPrefix, session.getTotalChunks(),
                session.getFileSize(), session.getMd5Hash(), session.getFileName());

        FileEntity file = buildTeamFileEntity(teamId, session.getUserId(), session.getParentId(), session.getFileName(),
                actual.size(), actual.md5(), objectKey);
        file = fileRepository.save(file);
        scheduleThumbnailAfterCommit(file);

        session.setStatus(2);
        session.setStoragePath(objectKey);
        session.setCompletedFileId(file.getId());
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionRepository.save(session);

        cleanupAfterCommit(chunkPrefix, session.getTotalChunks(), bitmapKey);
        if (session.getFileSize() != null && session.getFileSize() > 0) {
            incrementAfterCommit(TEAM_STORAGE_INCR_PREFIX + teamId, session.getFileSize());
        }
        notificationService.checkTeamQuota(teamId);
        teamAuditService.record(teamId, userId, "UPLOAD_COMPLETE", "FILE", file.getId(), file.getFileName());
        return file.getId();
    }

    @Override
    @Transactional
    public void cancelTeamUpload(Long teamId, String sessionId, Long userId) {
        UploadSessionEntity session = getTeamSessionForUpdate(teamId, sessionId, userId);
        lockTeamMutations(teamId);
        teamPermissionService.requireParentWrite(userId, teamId, session.getParentId());
        if (!Objects.equals(session.getStatus(), 1)) return;
        storageService.deleteParts(teamChunkPrefix(teamId, sessionId), session.getTotalChunks());
        redisTemplate.delete(UPLOAD_BITMAP_PREFIX + sessionId);
        session.setStatus(3);
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionRepository.save(session);
        teamAuditService.record(teamId, userId, "UPLOAD_CANCEL", "UPLOAD", null, sessionId);
    }

    @Override
    @Transactional
    public Long streamUpload(Long userId, Long parentId, String fileName,
                             Long fileSize, InputStream inputStream, String md5Hash) {
        validatePersonalParent(userId, parentId);
        checkStorageQuota(userId, fileSize);

        if (fileSize == null || fileSize < 0) throw new BusinessException(ApiCode.BAD_REQUEST);
        String expected = md5Hash == null ? null : normalizeMd5(md5Hash);
        String prefix = "uploads/" + userId + "/" + UUID.randomUUID() + "/chunks";
        int parts = 0;
        long received = 0;
        MessageDigest digest = newMd5();
        try {
            byte[] buffer = new byte[CHUNK_SIZE];
            int count;
            while ((count = readChunkFully(inputStream, buffer)) > 0) {
                received += count;
                if (received > fileSize) throw new BusinessException(ApiCode.UPLOAD_SIZE_MISMATCH);
                digest.update(buffer, 0, count);
                storageService.uploadPart(prefix, parts, new ByteArrayInputStream(buffer, 0, count), count);
                parts++;
            }
            if (received != fileSize) throw new BusinessException(ApiCode.UPLOAD_SIZE_MISMATCH);
            String actualMd5 = HexFormat.of().formatHex(digest.digest());
            if (expected != null && !expected.equals(actualMd5)) throw new BusinessException(ApiCode.FILE_MD5_MISMATCH);
            String key = generateStoragePath(userId, fileName);
            ContentDigest actual = composeVerified(key, prefix, parts, received, actualMd5, fileName);
            FileEntity file = fileRepository.save(buildFileEntity(userId, parentId, fileName, actual.size(), actual.md5(), key));
            scheduleThumbnailAfterCommit(file);
            cleanupAfterCommit(prefix, parts, null);
            if (received > 0) incrementAfterCommit(STORAGE_INCR_PREFIX + userId, received);
            return file.getId();
        } catch (IOException e) {
            int uploaded = parts;
            safeCleanup(() -> storageService.deleteParts(prefix, uploaded));
            throw new BusinessException(ApiCode.UPLOAD_FAILED);
        } catch (RuntimeException e) {
            int uploaded = parts;
            safeCleanup(() -> storageService.deleteParts(prefix, uploaded));
            throw e;
        }
    }

    private int readChunkFully(InputStream input, byte[] buffer) throws IOException {
        int length = 0;
        while (length < buffer.length) {
            int count = input.read(buffer, length, buffer.length - length);
            if (count < 0) break;
            if (count == 0) {
                int value = input.read();
                if (value < 0) break;
                buffer[length++] = (byte) value;
            } else length += count;
        }
        return length;
    }

    private int uploadChunkCount(Long size) {
        if (size == null || size < 0) throw new BusinessException(ApiCode.BAD_REQUEST);
        return chunkCount(size);
    }

    private int chunkCount(long size) {
        long chunks = size / CHUNK_SIZE + (size % CHUNK_SIZE == 0 ? 0 : 1);
        if (chunks > Integer.MAX_VALUE) throw new BusinessException(ApiCode.BAD_REQUEST);
        return (int) chunks;
    }

    private void validateUploadShape(Long size, int chunks) {
        if (size == null || size < 0 || chunks != chunkCount(size)) throw new BusinessException(ApiCode.BAD_REQUEST);
    }

    private String normalizeMd5(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{32}")) throw new BusinessException(ApiCode.BAD_REQUEST);
        return value.toLowerCase(Locale.ROOT);
    }

    private MessageDigest newMd5() {
        try { return MessageDigest.getInstance("MD5"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("MD5 unavailable", e); }
    }

    private void validateSession(UploadSessionEntity session) {
        try {
            validateUploadShape(session.getFileSize(), session.getTotalChunks());
            normalizeMd5(session.getMd5Hash());
            if (!Objects.equals(session.getChunkSize(), CHUNK_SIZE)) throw new BusinessException(ApiCode.BAD_REQUEST);
        } catch (BusinessException | NullPointerException e) {
            throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        }
    }

    private void receiveChunk(UploadSessionEntity session, String prefix, int number, byte[] data, String chunkMd5) {
        validateSession(session);
        String expected = normalizeMd5(chunkMd5);
        if (number < 0 || number >= session.getTotalChunks()) throw new BusinessException(ApiCode.CHUNK_UPLOAD_INVALID);
        long length = Math.min(CHUNK_SIZE, session.getFileSize() - (long) number * CHUNK_SIZE);
        if (data.length != length) throw new BusinessException(ApiCode.UPLOAD_SIZE_MISMATCH);
        String actual = HexFormat.of().formatHex(newMd5().digest(data));
        if (!actual.equals(expected)) throw new BusinessException(ApiCode.CHUNK_MD5_MISMATCH);
        String bitmapKey = UPLOAD_BITMAP_PREFIX + session.getId();
        if (Boolean.TRUE.equals(redisTemplate.opsForValue().getBit(bitmapKey, number))) {
            ContentDigest stored = inspectObject(prefix + ".part." + (number + 1));
            if (stored.size() != length || !stored.md5().equals(actual)) throw new BusinessException(ApiCode.CHUNK_MD5_MISMATCH);
            return;
        }
        storageService.uploadPart(prefix, number, new ByteArrayInputStream(data), data.length);
        redisTemplate.opsForValue().setBit(bitmapKey, number, true);
        redisTemplate.expire(bitmapKey, 24, TimeUnit.HOURS);
        session.setReceivedChunks(session.getReceivedChunks() + 1);
        session.setUpdatedAt(LocalDateTime.now());
        uploadSessionRepository.save(session);
    }

    private ContentDigest inspectObject(String key) {
        try { return storageService.inspectObject(key); }
        catch (ObjectNotFoundException e) { throw e; }
        catch (RuntimeException e) { throw new BusinessException(ApiCode.STORAGE_ERROR); }
    }

    private Optional<FileEntity> verifiedInstantCandidate(Long size, String md5) {
        if (size == null || size < 0) throw new BusinessException(ApiCode.BAD_REQUEST);
        for (FileEntity candidate : fileRepository.findByMd5HashIgnoreCaseAndFileSizeAndIsDeletedFalseOrderByIdDesc(md5, size)) {
            if (candidate.getStoragePath() == null || !"file".equals(candidate.getFileType())) continue;
            try {
                ContentDigest actual = inspectObject(candidate.getStoragePath());
                if (actual.size() == size && actual.md5().equals(md5)) return Optional.of(candidate);
            } catch (ObjectNotFoundException e) {
                // Absent candidates are misses; storage outages propagate.
            }
        }
        return Optional.empty();
    }

    private ContentDigest composeVerified(String key, String prefix, int parts, long size, String md5, String name) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) safeCleanup(() -> storageService.deleteObject(key));
                }
            });
        }
        try {
            ContentDigest actual;
            if (size == 0) {
                storageService.uploadObject(key, new ByteArrayInputStream(new byte[0]), 0, guessMimeType(name));
                actual = new ContentDigest(0, HexFormat.of().formatHex(newMd5().digest()));
            } else {
                actual = storageService.composeParts(key, prefix, parts);
            }
            if (actual.size() != size) throw new BusinessException(ApiCode.UPLOAD_SIZE_MISMATCH);
            if (!actual.md5().equalsIgnoreCase(md5)) throw new BusinessException(ApiCode.FILE_MD5_MISMATCH);
            return actual;
        } catch (RuntimeException e) {
            safeCleanup(() -> storageService.deleteObject(key));
            if (e instanceof BusinessException) throw e;
            throw new BusinessException(ApiCode.STORAGE_ERROR);
        }
    }

    private void incrementAfterCommit(String key, long size) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { safeCleanup(() -> redisTemplate.opsForValue().increment(key, size)); }
            });
        } else redisTemplate.opsForValue().increment(key, size);
    }

    private void cleanupAfterCommit(String prefix, int parts, String bitmapKey) {
        Runnable cleanup = () -> {
            safeCleanup(() -> storageService.deleteParts(prefix, parts));
            if (bitmapKey != null) safeCleanup(() -> redisTemplate.delete(bitmapKey));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanup.run(); }
            });
        } else cleanup.run();
    }

    private void safeCleanup(Runnable cleanup) {
        try { cleanup.run(); }
        catch (RuntimeException e) { log.warn("Upload cleanup failed; exception type={}", e.getClass().getSimpleName()); }
    }

    private String generateStoragePath(Long userId, String fileName) {
        LocalDateTime now = LocalDateTime.now();
        String randomId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return userId + "/" + now.getYear() + "/"
                + String.format("%02d", now.getMonthValue()) + "/"
                + randomId + "_" + fileName;
    }

    private void scheduleThumbnailAfterCommit(FileEntity file) {
        if (!shouldGenerateThumbnail(file)) {
            return;
        }
        Long fileId = file.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    thumbnailService.generateThumbnailAsync(fileId);
                }
            });
            return;
        }
        thumbnailService.generateThumbnailAsync(fileId);
    }

    private boolean shouldGenerateThumbnail(FileEntity file) {
        if (file == null || file.getId() == null || !"file".equals(file.getFileType())) {
            return false;
        }
        String mimeType = file.getMimeType();
        return mimeType != null && mimeType.toLowerCase().startsWith("image/");
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
                || !hasActiveTeamAncestors(parent)
                || !"folder".equals(parent.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
    }

    private boolean hasActiveTeamAncestors(FileEntity file) {
        Long parentId = file.getParentId();
        Set<Long> seen = new HashSet<>();
        while (parentId != null && seen.add(parentId)) {
            FileEntity parent = fileRepository.findById(parentId).orElse(null);
            if (parent == null || parent.getIsDeleted() || !Objects.equals(parent.getTeamId(), file.getTeamId())) return false;
            parentId = parent.getParentId();
        }
        return parentId == null;
    }

    private void lockTeamMutations(Long teamId) {
        if (teamRepository.findActiveByIdForUpdate(teamId).isEmpty()) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Team not found");
        }
    }

    private String normalizeFileName(String fileName) {
        String normalized = fileName == null ? "" : fileName.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_FILE_NAME_LENGTH) {
            throw new BusinessException(ApiCode.BAD_REQUEST, INVALID_FILE_NAME, Map.of());
        }
        return normalized;
    }

    private void requireTeamNameAvailable(Long teamId, Long parentId, String fileName) {
        boolean exists = parentId == null
                ? fileRepository.existsByTeamIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(teamId, fileName)
                : fileRepository.existsByTeamIdAndParentIdAndFileNameAndIsDeletedFalse(teamId, parentId, fileName);
        if (exists) throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
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
