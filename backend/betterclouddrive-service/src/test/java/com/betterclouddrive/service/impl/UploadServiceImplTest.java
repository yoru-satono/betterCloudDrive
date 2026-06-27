package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.UploadSessionEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.UploadSessionRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadServiceImplTest {

    @Mock private UploadSessionRepository uploadSessionRepository;
    @Mock private FileRepository fileRepository;
    @Mock private UserRepository userRepository;
    @Mock private StorageService storageService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @InjectMocks private UploadServiceImpl uploadService;

    private UserEntity quotaUser(long quota, long used) {
        return UserEntity.builder().id(1L).storageQuota(quota).storageUsed(used).build();
    }

    private UploadSessionEntity activeSession(String id, int totalChunks) {
        return UploadSessionEntity.builder()
                .id(id).userId(1L).fileName("test.bin")
                .fileSize(1024L).totalChunks(totalChunks)
                .receivedChunks(0).status(1).build();
    }

    // ── initUpload quota ──────────────────────────────────────────────────────

    @Test
    void initUpload_shouldThrowWhenQuotaExceeded() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(quotaUser(1024L, 1000L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);

        assertThatThrownBy(() -> uploadService.initUpload(1L, null, "big.bin", 100L, null, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419001));
    }

    @Test
    void initUpload_shouldThrowWhenQuotaExceededWithPendingIncr() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(quotaUser(1024L, 500L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn("600");

        assertThatThrownBy(() -> uploadService.initUpload(1L, null, "big.bin", 100L, null, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419001));
    }

    @Test
    void initUpload_shouldSucceedWhenQuotaSufficient() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(quotaUser(10737418240L, 0L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);
        when(uploadSessionRepository.save(any(UploadSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        uploadService.initUpload(1L, null, "small.bin", 100L, null, 1);

        verify(uploadSessionRepository).save(any(UploadSessionEntity.class));
    }

    @Test
    void initUpload_shouldAllowZeroByteFilesWithoutChunks() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(quotaUser(10737418240L, 0L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);
        when(uploadSessionRepository.save(any(UploadSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UploadSessionEntity session = uploadService.initUpload(1L, null, "empty.txt", 0L, "d41d8cd98f00b204e9800998ecf8427e", 0);

        assertThat(session.getFileSize()).isZero();
        assertThat(session.getTotalChunks()).isZero();
    }

    @Test
    void initUpload_shouldRejectPositiveFilesWithoutChunks() {
        assertThatThrownBy(() -> uploadService.initUpload(1L, null, "bad.bin", 10L, null, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
        verifyNoInteractions(userRepository);
    }

    // ── uploadChunk ───────────────────────────────────────────────────────────

    @Test
    void uploadChunk_shouldUploadAndSetBitmap() {
        UploadSessionEntity session = activeSession("sess-1", 3);
        when(uploadSessionRepository.findById("sess-1")).thenReturn(Optional.of(session));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getBit("upload:bitmap:sess-1", 0)).thenReturn(false);

        byte[] data = new byte[100];
        uploadService.uploadChunk("sess-1", 1L, 0, data, null);

        verify(storageService).uploadPart(eq("uploads/1/sess-1/chunks"), eq(0), any(), eq(100L));
        verify(valueOps).setBit("upload:bitmap:sess-1", 0, true);
        verify(uploadSessionRepository).save(session);
    }

    @Test
    void uploadChunk_shouldSkipAlreadyUploadedChunk() {
        UploadSessionEntity session = activeSession("sess-2", 3);
        when(uploadSessionRepository.findById("sess-2")).thenReturn(Optional.of(session));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getBit("upload:bitmap:sess-2", 1)).thenReturn(true);

        uploadService.uploadChunk("sess-2", 1L, 1, new byte[50], null);

        verify(storageService, never()).uploadPart(any(), anyInt(), any(), anyInt());
    }

    @Test
    void uploadChunk_shouldThrowWhenSessionNotFound() {
        when(uploadSessionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> uploadService.uploadChunk("missing", 1L, 0, new byte[10], null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void uploadChunk_shouldThrowWhenInvalidChunkNumber() {
        UploadSessionEntity session = activeSession("sess-3", 2);
        when(uploadSessionRepository.findById("sess-3")).thenReturn(Optional.of(session));

        // chunk number >= totalChunks is invalid
        assertThatThrownBy(() -> uploadService.uploadChunk("sess-3", 1L, 5, new byte[10], null))
                .isInstanceOf(BusinessException.class);
    }

    // ── getUploadStatus ───────────────────────────────────────────────────────

    @Test
    void getUploadStatus_shouldReturnMissingChunks() {
        UploadSessionEntity session = UploadSessionEntity.builder()
                .id("sess-4").userId(1L).totalChunks(3).receivedChunks(2).status(1).build();
        when(uploadSessionRepository.findById("sess-4")).thenReturn(Optional.of(session));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getBit("upload:bitmap:sess-4", 0)).thenReturn(true);
        when(valueOps.getBit("upload:bitmap:sess-4", 1)).thenReturn(true);
        when(valueOps.getBit("upload:bitmap:sess-4", 2)).thenReturn(false);

        Map<String, Object> status = uploadService.getUploadStatus("sess-4", 1L);

        assertThat(status.get("totalChunks")).isEqualTo(3);
        assertThat(status.get("uploadedChunks")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        java.util.List<Integer> missing = (java.util.List<Integer>) status.get("missingChunks");
        assertThat(missing).containsExactly(2);
    }

    // ── completeUpload ────────────────────────────────────────────────────────

    @Test
    void completeUpload_shouldCreateFileEntity() {
        UploadSessionEntity session = UploadSessionEntity.builder()
                .id("sess-5").userId(1L).fileName("doc.pdf").fileSize(512L)
                .totalChunks(1).status(1).build();
        when(uploadSessionRepository.findById("sess-5")).thenReturn(Optional.of(session));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getBit("upload:bitmap:sess-5", 0)).thenReturn(true);
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> {
            FileEntity f = inv.getArgument(0);
            f.setId(99L);
            return f;
        });
        when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Long fileId = uploadService.completeUpload("sess-5", 1L);

        assertThat(fileId).isEqualTo(99L);
        verify(storageService).composeParts(anyString(), eq("uploads/1/sess-5/chunks"), eq(1));
        verify(redisTemplate).delete("upload:bitmap:sess-5");
    }

    @Test
    void completeUpload_shouldCreateZeroByteObjectWithoutPartsOrStorageIncrement() {
        UploadSessionEntity session = UploadSessionEntity.builder()
                .id("empty-session").userId(1L).fileName("empty.txt").fileSize(0L)
                .md5Hash("d41d8cd98f00b204e9800998ecf8427e")
                .totalChunks(0).status(1).build();
        when(uploadSessionRepository.findById("empty-session")).thenReturn(Optional.of(session));
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> {
            FileEntity f = inv.getArgument(0);
            f.setId(100L);
            return f;
        });
        when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Long fileId = uploadService.completeUpload("empty-session", 1L);

        assertThat(fileId).isEqualTo(100L);
        verify(storageService).uploadObject(anyString(), any(InputStream.class), eq(0L), eq("text/plain"));
        verify(storageService, never()).composeParts(anyString(), anyString(), anyInt());
        verify(redisTemplate).delete("upload:bitmap:empty-session");
        verify(valueOps, never()).increment(anyString(), anyLong());
    }

    @Test
    void completeUpload_shouldThrowWhenChunkMissing() {
        UploadSessionEntity session = UploadSessionEntity.builder()
                .id("sess-6").userId(1L).totalChunks(2).status(1).build();
        when(uploadSessionRepository.findById("sess-6")).thenReturn(Optional.of(session));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.getBit("upload:bitmap:sess-6", 0)).thenReturn(false);

        assertThatThrownBy(() -> uploadService.completeUpload("sess-6", 1L))
                .isInstanceOf(BusinessException.class);
    }

    // ── cancelUpload ──────────────────────────────────────────────────────────

    @Test
    void cancelUpload_shouldMarkSessionCanceled() {
        UploadSessionEntity session = activeSession("sess-7", 2);
        when(uploadSessionRepository.findById("sess-7")).thenReturn(Optional.of(session));
        when(uploadSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        uploadService.cancelUpload("sess-7", 1L);

        assertThat(session.getStatus()).isEqualTo(3);
        verify(storageService).deleteParts("uploads/1/sess-7/chunks", 2);
        verify(redisTemplate).delete("upload:bitmap:sess-7");
    }

    // ── instantUpload ─────────────────────────────────────────────────────────

    @Test
    void instantUpload_shouldThrowWhenQuotaExceeded() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(quotaUser(100L, 90L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);

        assertThatThrownBy(() -> uploadService.instantUpload(1L, null, "f", 20L, "md5"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419001));
    }

    @Test
    void instantUpload_shouldReturnExistingFileId() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(quotaUser(10737418240L, 0L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);
        FileEntity existing = FileEntity.builder().id(5L).storagePath("path/to/obj").build();
        when(fileRepository.findFirstByMd5HashAndIsDeletedFalseOrderByCreatedAtDesc("abc123"))
                .thenReturn(Optional.of(existing));
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> {
            FileEntity f = inv.getArgument(0);
            f.setId(10L);
            return f;
        });

        Long fileId = uploadService.instantUpload(1L, null, "copy.txt", 100L, "abc123");

        assertThat(fileId).isEqualTo(10L);
        verify(valueOps).increment("storage:incr:1", 100L);
    }

    // ── streamUpload ──────────────────────────────────────────────────────────

    @Test
    void streamUpload_shouldUploadInChunksAndReturnFileId() {
        // 11 MB → 3 chunks (5MB + 5MB + 1MB)
        int dataSize = 11 * 1024 * 1024;
        byte[] data = new byte[dataSize];
        InputStream stream = new ByteArrayInputStream(data);

        when(userRepository.findById(1L)).thenReturn(Optional.of(quotaUser(10737418240L, 0L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> {
            FileEntity f = inv.getArgument(0);
            f.setId(42L);
            return f;
        });

        Long fileId = uploadService.streamUpload(1L, 10L, "large.bin", (long) dataSize, stream, null);

        assertThat(fileId).isEqualTo(42L);
        // 3 chunks uploaded
        verify(storageService, times(3)).uploadPart(anyString(), anyInt(), any(InputStream.class), anyLong());
        // parts composed into final object
        verify(storageService).composeParts(anyString(), anyString(), eq(3));
        // storage counter incremented
        verify(valueOps).increment(eq("storage:incr:1"), eq((long) dataSize));
    }

    @Test
    void streamUpload_shouldComputeMd5WhenNotProvided() {
        byte[] data = "hello world".getBytes();
        InputStream stream = new ByteArrayInputStream(data);

        when(userRepository.findById(1L)).thenReturn(Optional.of(quotaUser(10737418240L, 0L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> {
            FileEntity f = inv.getArgument(0);
            f.setId(7L);
            return f;
        });

        uploadService.streamUpload(1L, null, "hello.txt", (long) data.length, stream, null);

        // Capture saved entity and verify md5 was computed (known MD5 of "hello world")
        org.mockito.ArgumentCaptor<FileEntity> captor = org.mockito.ArgumentCaptor.forClass(FileEntity.class);
        verify(fileRepository).save(captor.capture());
        assertThat(captor.getValue().getMd5Hash()).isEqualTo("5eb63bbbe01eeed093cb22bb8f5acdc3");
    }

    @Test
    void streamUpload_shouldUseProvidedMd5WithoutRecomputing() {
        byte[] data = "test".getBytes();
        InputStream stream = new ByteArrayInputStream(data);

        when(userRepository.findById(1L)).thenReturn(Optional.of(quotaUser(10737418240L, 0L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> {
            FileEntity f = inv.getArgument(0);
            f.setId(8L);
            return f;
        });

        uploadService.streamUpload(1L, null, "test.txt", (long) data.length, stream, "precomputed-md5");

        org.mockito.ArgumentCaptor<FileEntity> captor = org.mockito.ArgumentCaptor.forClass(FileEntity.class);
        verify(fileRepository).save(captor.capture());
        assertThat(captor.getValue().getMd5Hash()).isEqualTo("precomputed-md5");
    }

    @Test
    void streamUpload_shouldThrowWhenQuotaExceeded() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(quotaUser(100L, 90L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);

        InputStream stream = new ByteArrayInputStream(new byte[20]);
        assertThatThrownBy(() -> uploadService.streamUpload(1L, null, "f.bin", 20L, stream, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419001));

        verifyNoInteractions(storageService);
    }
}
