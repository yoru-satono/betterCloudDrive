package com.betterclouddrive.service.impl;

import com.betterclouddrive.service.quota.QuotaAccountingService;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Scope;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.common.dto.UploadInitResult;
import com.betterclouddrive.dal.entity.*;
import com.betterclouddrive.dal.repository.*;
import com.betterclouddrive.service.*;
import com.betterclouddrive.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.*;
import org.springframework.transaction.support.*;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class UploadIntegrityTest {
    private static final String MD5 = "5d41402abc4b2a76b9719d911017c592"; // hello
    @Mock UploadSessionRepository sessions;
    @Mock FileRepository files;
    @Mock UserRepository users;
    @Mock TeamRepository teams;
    @Mock StorageService storage;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> bits;
    @Mock TeamAuditService audit;
    @Mock NotificationService notifications;
    @Mock TeamPermissionService permissions;
    @Mock ThumbnailService thumbnails;
    @Mock com.betterclouddrive.service.WarehouseEventService warehouseEvents;
    @Mock private QuotaAccountingService quotaAccounting;
    @Mock private com.betterclouddrive.service.inspection.ActiveObjectPrefixService activeObjects;
    @InjectMocks UploadServiceImpl service;

    @AfterEach void clearTransactions() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    private UploadSessionEntity session(boolean team) {
        var session = UploadSessionEntity.builder().id("session").userId(1L).teamId(team ? 10L : null)
                .fileName("hello.txt").fileSize(5L).chunkSize(5242880).totalChunks(1)
                .md5Hash(MD5).status(1).receivedChunks(0).build();
        when(sessions.findByIdForUpdate("session")).thenReturn(Optional.of(session));
        if (team) when(teams.findActiveByIdForUpdate(10L)).thenReturn(Optional.of(TeamEntity.builder().id(10L).build()));
        return session;
    }

    private void chunk(boolean team, byte[] bytes, String md5) {
        if (team) service.uploadTeamChunk(10L, "session", 1L, 0, bytes, md5);
        else service.uploadChunk("session", 1L, 0, bytes, md5);
    }

    private Long complete(boolean team) {
        return team ? service.completeTeamUpload(10L, "session", 1L) : service.completeUpload("session", 1L);
    }

    private void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(code));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsMissingOrInvalidChunkDigestWithoutSideEffects(boolean team) {
        session(team);
        for (String md5 : Arrays.asList(null, "", "not-md5", "z".repeat(32))) {
            assertCode(() -> chunk(team, "hello".getBytes(), md5), 400);
        }
        verifyNoInteractions(storage, redis);
        verify(sessions, never()).save(any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsIncorrectDigestAndLengthBeforeWriting(boolean team) {
        session(team);
        assertCode(() -> chunk(team, "world".getBytes(), MD5), 419007);
        assertCode(() -> chunk(team, new byte[4], MD5), 419017);
        assertCode(() -> chunk(team, new byte[6], MD5), 419017);
        verifyNoInteractions(storage, redis);
        verify(sessions, never()).save(any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void verifiesDuplicateContentAndAcceptsUppercaseDigest(boolean team) {
        session(team);
        when(redis.opsForValue()).thenReturn(bits);
        when(bits.getBit("upload:bitmap:session", 0)).thenReturn(true);
        when(storage.inspectObject(anyString())).thenReturn(new ContentDigest(5, MD5));
        chunk(team, "hello".getBytes(), MD5.toUpperCase(Locale.ROOT));
        when(storage.inspectObject(anyString())).thenReturn(new ContentDigest(5, "a".repeat(32)));
        assertCode(() -> chunk(team, "hello".getBytes(), MD5), 419007);
        verify(storage, never()).uploadPart(anyString(), anyInt(), any(), anyLong());
        verify(bits, never()).setBit(anyString(), anyLong(), anyBoolean());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedWholeFileVerificationPreservesResumeDataAndQuota(boolean team) {
        var session = session(team);
        when(redis.opsForValue()).thenReturn(bits);
        when(bits.getBit("upload:bitmap:session", 0)).thenReturn(true);
        when(storage.composeParts(anyString(), anyString(), eq(1))).thenReturn(new ContentDigest(5, "a".repeat(32)));
        assertCode(() -> complete(team), 419016);
        assertThat(session.getStatus()).isEqualTo(1);
        verify(files, never()).save(any());
        verify(sessions, never()).save(any());
        verify(storage).deleteObject(anyString());
        verify(storage, never()).deleteParts(anyString(), anyInt());
        verify(redis, never()).delete(anyString());
        verify(quotaAccounting, never()).change(any(), anyLong(), anyLong());
        verifyNoInteractions(thumbnails, audit);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cleanupIsDeferredUntilCommitAndRollbackRemovesOnlyFinalObject(boolean team) {
        session(team);
        when(redis.opsForValue()).thenReturn(bits);
        when(bits.getBit("upload:bitmap:session", 0)).thenReturn(true);
        when(storage.composeParts(anyString(), anyString(), eq(1))).thenReturn(new ContentDigest(5, MD5));
        when(files.save(any())).thenAnswer(inv -> { FileEntity file = inv.getArgument(0); file.setId(99L); return file; });
        TransactionSynchronizationManager.initSynchronization();
        assertThat(complete(team)).isEqualTo(99L);
        verify(storage, never()).deleteParts(anyString(), anyInt());
        verify(redis, never()).delete(anyString());
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(quotaAccounting).change(team ? Scope.TEAM : Scope.PERSONAL, team ? 10L : 1L, 5L);
        verify(storage).deleteObject(anyString());
        verify(storage, never()).deleteParts(anyString(), anyInt());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void commitCleansPartsAndCleanupFailureDoesNotChangeResult(boolean team) {
        session(team);
        when(redis.opsForValue()).thenReturn(bits);
        when(bits.getBit("upload:bitmap:session", 0)).thenReturn(true);
        when(storage.composeParts(anyString(), anyString(), eq(1))).thenReturn(new ContentDigest(5, MD5));
        when(files.save(any())).thenAnswer(inv -> { FileEntity file = inv.getArgument(0); file.setId(99L); return file; });
        TransactionSynchronizationManager.initSynchronization();
        complete(team);
        doThrow(new IllegalStateException()).when(storage).deleteParts(anyString(), eq(1));
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertThatCode(() -> callbacks.forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
        callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(redis).delete("upload:bitmap:session");
        verify(storage, never()).deleteObject(anyString());
    }

    @Test void rejectsInvalidInitShapeAndNormalizesDigest() {
        assertCode(() -> service.initUpload(1L, null, "hello", 5L, null), 400);
        assertCode(() -> service.initUpload(1L, null, "hello", -1L, MD5), 400);
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.initUpload(1L, null, "hello", 5L, MD5.toUpperCase(Locale.ROOT)).mode()).isEqualTo(UploadInitResult.Mode.MULTIPART);
        var capture = ArgumentCaptor.forClass(UploadSessionEntity.class);
        verify(sessions).save(capture.capture());
        assertThat(capture.getValue().getMd5Hash()).isEqualTo(MD5);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void initializationHitRecordsQuotaInsideBusinessTransaction(boolean team) {
        if (team) {
            when(teams.findActiveByIdForUpdate(10L)).thenReturn(Optional.of(TeamEntity.builder().id(10L).build()));
        }
        var source = FileEntity.builder().storagePath("verified").build();
        when(files.findByMd5HashIgnoreCaseAndFileSizeAndIsDeletedFalseOrderByIdDesc(MD5, 5L)).thenReturn(List.of(source));
        when(storage.inspectObject("verified")).thenReturn(new ContentDigest(5, MD5));
        when(files.save(any())).thenAnswer(inv -> { FileEntity file = inv.getArgument(0); file.setId(99L); return file; });
        TransactionSynchronizationManager.initSynchronization();
        var result = team ? service.initTeamUpload(10L, 1L, null, "copy", 5L, MD5) : service.initUpload(1L, null, "copy", 5L, MD5);
        assertThat(result.mode()).isEqualTo(UploadInitResult.Mode.INSTANT);
        assertThat(result.fileId()).isEqualTo(99L);
        verify(warehouseEvents).uploadCommitted(argThat(file -> file.getId().equals(99L)
                && java.util.Objects.equals(file.getTeamId(), team ? 10L : null)),
                eq(com.betterclouddrive.common.dto.WarehouseEvent.Mode.INSTANT));
        verifyNoInteractions(sessions);
        verify(quotaAccounting).change(team ? Scope.TEAM : Scope.PERSONAL, team ? 10L : 1L, 5L);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(quotaAccounting, times(1)).change(team ? Scope.TEAM : Scope.PERSONAL, team ? 10L : 1L, 5L);
        if (team) verify(audit).record(10L, 1L, "UPLOAD_INSTANT", "FILE", 99L, "copy");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void initializationDerivesBoundaryChunkCountsWithoutQuotaSideEffects(boolean team) {
        if (team) {
            when(teams.findActiveByIdForUpdate(10L)).thenReturn(Optional.of(TeamEntity.builder().id(10L).build()));
        }
        for (long size : new long[] {0, 1, 5242880, 5242881}) {
            var result = team ? service.initTeamUpload(10L, 1L, null, "file", size, MD5) : service.initUpload(1L, null, "file", size, MD5);
            assertThat(result.totalChunks()).isEqualTo((int) ((size + 5242879) / 5242880));
            assertThat(result.mode()).isEqualTo(UploadInitResult.Mode.MULTIPART);
        }
        verifyNoInteractions(storage, thumbnails, notifications, audit);
        verify(files, never()).save(any());
        verify(quotaAccounting, never()).change(any(), anyLong(), anyLong());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void quotaDenialPrecedesCandidateLookup(boolean team) {
        doThrow(new BusinessException(com.betterclouddrive.common.constant.ApiCode.STORAGE_QUOTA_EXCEEDED))
                .when(quotaAccounting).check(team ? Scope.TEAM : Scope.PERSONAL, team ? 10L : 1L, 5L);
        if (team) {
            when(teams.findActiveByIdForUpdate(10L)).thenReturn(Optional.of(TeamEntity.builder().id(10L).build()));
        }
        assertCode(() -> { if (team) service.initTeamUpload(10L, 1L, null, "file", 5L, MD5); else service.initUpload(1L, null, "file", 5L, MD5); }, 419001);
        verifyNoInteractions(storage, sessions);
        verify(files, never()).findByMd5HashIgnoreCaseAndFileSizeAndIsDeletedFalseOrderByIdDesc(anyString(), anyLong());
    }

    @Test void teamPermissionDenialPrecedesCandidateLookup() {
        when(teams.findActiveByIdForUpdate(10L)).thenReturn(Optional.of(TeamEntity.builder().id(10L).build()));
        doThrow(new BusinessException(com.betterclouddrive.common.constant.ApiCode.FORBIDDEN)).when(permissions).requireParentWrite(1L, 10L, null);
        assertCode(() -> service.initTeamUpload(10L, 1L, null, "file", 5L, MD5), 403);
        verifyNoInteractions(storage, files, sessions, redis);
    }

    @Test void oldSessionCannotCompleteOrReceiveChunks() {
        var session = session(false);
        session.setMd5Hash(null);
        when(sessions.findById("session")).thenReturn(Optional.of(session));
        assertCode(() -> service.getUploadStatus("session", 1L), 419006);
        assertCode(() -> complete(false), 419006);
        assertCode(() -> chunk(false, "hello".getBytes(), MD5), 419006);
        verifyNoInteractions(storage, redis);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void instantChecksEveryCandidateAndPropagatesStorageOutage(boolean team) {
        if (team) {
            when(teams.findActiveByIdForUpdate(10L)).thenReturn(Optional.of(TeamEntity.builder().id(10L).build()));
        }
        var absent = FileEntity.builder().storagePath("absent").build();
        var corrupt = FileEntity.builder().storagePath("corrupt").build();
        var wrongSize = FileEntity.builder().storagePath("wrong-size").build();
        when(files.findByMd5HashIgnoreCaseAndFileSizeAndIsDeletedFalseOrderByIdDesc(MD5, 5L)).thenReturn(List.of(absent, corrupt, wrongSize));
        when(storage.inspectObject("absent")).thenThrow(new ObjectNotFoundException(null));
        when(storage.inspectObject("corrupt")).thenReturn(new ContentDigest(5, "a".repeat(32)));
        when(storage.inspectObject("wrong-size")).thenReturn(new ContentDigest(4, MD5));
        Runnable instant = () -> { if (team) service.initTeamUpload(10L, 1L, null, "copy", 5L, MD5); else service.initUpload(1L, null, "copy", 5L, MD5).fileId(); };
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        instant.run();
        verify(sessions).save(any());
        verify(storage).inspectObject("wrong-size");
        when(storage.inspectObject("corrupt")).thenThrow(new IllegalStateException());
        assertCode(instant, 500001);
        verify(files, never()).save(any());
        verify(quotaAccounting, never()).change(any(), anyLong(), anyLong());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void wrongComposedSizeCannotPublishMetadata(boolean team) {
        session(team);
        when(redis.opsForValue()).thenReturn(bits);
        when(bits.getBit("upload:bitmap:session", 0)).thenReturn(true);
        when(storage.composeParts(anyString(), anyString(), eq(1))).thenReturn(new ContentDigest(4, MD5));
        assertCode(() -> complete(team), 419017);
        verify(files, never()).save(any());
        verify(storage, never()).deleteParts(anyString(), anyInt());
        verify(quotaAccounting, never()).change(any(), anyLong(), anyLong());
    }

    @Test void shortStreamReadsStillFormOneCompletePart() {
        when(storage.composeParts(anyString(), anyString(), eq(1))).thenReturn(new ContentDigest(5, MD5));
        when(files.save(any())).thenAnswer(inv -> { FileEntity file = inv.getArgument(0); file.setId(1L); return file; });
        var input = new ByteArrayInputStream("hello".getBytes()) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(1, length));
            }
        };
        assertThat(service.streamUpload(1L, null, "hello", 5L, input, MD5)).isEqualTo(1L);
        verify(storage).uploadPart(anyString(), eq(0), any(), eq(5L));
        verify(storage, times(1)).uploadPart(anyString(), anyInt(), any(), anyLong());
    }

    @Test void completedStatusDoesNotConsultAnExpiredBitmap() {
        var session = UploadSessionEntity.builder().id("done").userId(1L).fileSize(5L).totalChunks(1)
                .chunkSize(5242880).md5Hash(MD5).status(2).build();
        when(sessions.findById("done")).thenReturn(Optional.of(session));
        var status = service.getUploadStatus("done", 1L);
        assertThat(status.get("missingChunks")).isEqualTo(List.of());
        assertThat(status.get("uploadedChunks")).isEqualTo(1);
        verify(redis, never()).opsForValue();
    }

    @Test void streamChecksSuppliedDigestAndDeclaredLengthBeforeComposition() {
        assertCode(() -> service.streamUpload(1L, null, "hello", 5L, new ByteArrayInputStream("world".getBytes()), MD5), 419016);
        assertCode(() -> service.streamUpload(1L, null, "hello", 6L, new ByteArrayInputStream("hello".getBytes()), MD5), 419017);
        assertCode(() -> service.streamUpload(1L, null, "hello", 4L, new ByteArrayInputStream("hello".getBytes()), MD5), 419017);
        verify(storage, never()).composeParts(anyString(), anyString(), anyInt());
        verify(files, never()).save(any());
        verify(quotaAccounting, never()).change(any(), anyLong(), anyLong());
    }
}
