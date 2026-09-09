package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FileVersionRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class FileVersionServiceImplTest {

    @Mock private FileVersionRepository fileVersionRepository;
    @Mock private FileRepository fileRepository;
    @Mock private UserRepository userRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private StorageService storageService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private TeamAuditService teamAuditService;
    @Mock private NotificationService notificationService;
    @InjectMocks private FileVersionServiceImpl fileVersionService;

    @BeforeEach
    void quotaDefaults() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.of(
                UserEntity.builder().id(1L).storageUsed(0L).storageQuota(Long.MAX_VALUE).build()));
        lenient().when(teamRepository.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Optional.of(
                TeamEntity.builder().id(10L).storageUsed(0L).storageQuota(Long.MAX_VALUE).build()));
    }

    private FileEntity ownedFile(Long fileId, Long userId) {
        return FileEntity.builder()
                .id(fileId)
                .userId(userId)
                .teamId(null)
                .fileName("doc.txt")
                .fileType("file")
                .fileSize(20L)
                .md5Hash("current-md5")
                .storagePath("users/1/current.txt")
                .isDeleted(false)
                .versionCount(2)
                .build();
    }

    private FileEntity teamFile(Long fileId, Long userId, Long teamId) {
        return FileEntity.builder()
                .id(fileId)
                .userId(userId)
                .teamId(teamId)
                .fileName("doc.docx")
                .fileType("file")
                .mimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .fileSize(12L)
                .md5Hash("old-md5")
                .storagePath("teams/10/old.docx")
                .isDeleted(false)
                .versionCount(1)
                .build();
    }

    private FileVersionEntity version(int num, String path) {
        return FileVersionEntity.builder().id((long) num).fileId(1L)
                .versionNumber(num).fileSize(10L).md5Hash("md5-v" + num).storagePath(path).build();
    }

    @Test
    void listVersions_shouldReturnTop10Versions() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));
        List<FileVersionEntity> versions = List.of(version(2, "p2"), version(1, "p1"));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(versions);

        List<FileVersionEntity> result = fileVersionService.listVersions(1L, 1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getVersionNumber()).isEqualTo(2);
    }

    @Test
    void listVersions_shouldThrowWhenFileNotFound() {
        when(fileRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileVersionService.listVersions(1L, 99L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listVersions_shouldThrowWhenNotOwner() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 2L)));

        assertThatThrownBy(() -> fileVersionService.listVersions(1L, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listVersions_shouldRejectTeamFileOnPersonalEndpoint() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(teamFile(1L, 1L, 10L)));

        assertThatThrownBy(() -> fileVersionService.listVersions(1L, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteVersion_shouldRemoveVersionAndDecrementCount() {
        FileEntity file = ownedFile(1L, 1L);
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(file));
        List<FileVersionEntity> versions = List.of(version(2, "path/v2"), version(1, "path/v1"));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(versions);
        when(fileRepository.save(any())).thenReturn(file);

        fileVersionService.deleteVersion(1L, 1L, 1);

        verify(storageService).deleteObject("path/v1");
        verify(fileVersionRepository).deleteById(1L);
        assertThat(file.getVersionCount()).isEqualTo(2);
    }

    @Test
    void deleteVersion_shouldThrowWhenVersionNotFound() {
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(List.of(version(1, "path/v1")));

        assertThatThrownBy(() -> fileVersionService.deleteVersion(1L, 1L, 99))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteVersion_shouldThrowWhenOnlyOneVersionExists() {
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(List.of(version(1, "path/v1")));

        // size() == 1, version exists but only one — should throw conflict
        assertThatThrownBy(() -> fileVersionService.deleteVersion(1L, 1L, 1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void saveTeamEditedVersion_shouldArchiveCurrentFileAndReplaceStoragePath() {
        FileEntity file = teamFile(1L, 2L, 10L);
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(file));
        when(fileVersionRepository.findMaxVersionNumberByFileId(1L)).thenReturn(0);
        when(fileVersionRepository.save(any(FileVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        FileVersionEntity archived = fileVersionService.saveTeamEditedVersion(10L, 2L, 1L, "new-content".getBytes());

        assertThat(archived.getVersionNumber()).isEqualTo(1);
        assertThat(archived.getStoragePath()).isEqualTo("teams/10/old.docx");
        assertThat(file.getStoragePath()).startsWith("teams/10/office/1/");
        assertThat(file.getFileSize()).isEqualTo(11L);
        assertThat(file.getMd5Hash()).isEqualTo("e92c4f27d783ac09065352d0e0f7cb8b");
        assertThat(file.getVersionCount()).isEqualTo(2);
        verify(storageService).uploadObject(startsWith("teams/10/office/1/"), any(), eq(11L), anyString());
        verify(valueOps).increment("team:storage:incr:10", 11L);
        verify(teamAuditService).record(eq(10L), eq(2L), eq("OFFICE_SAVE_VERSION"), eq("FILE"), eq(1L),
                contains("archived version 1"));
    }

    @Test
    void deleteTeamVersion_shouldRemoveVersionAndWriteAuditLog() {
        FileEntity file = teamFile(1L, 2L, 10L);
        file.setVersionCount(2);
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(file));
        List<FileVersionEntity> versions = List.of(version(2, "teams/10/v2.docx"), version(1, "teams/10/v1.docx"));
        when(fileVersionRepository.findTopVersionsByFileId(eq(1L), any(PageRequest.class)))
                .thenReturn(versions);
        when(fileRepository.save(any())).thenReturn(file);

        fileVersionService.deleteTeamVersion(10L, 2L, 1L, 1);

        verify(storageService).deleteObject("teams/10/v1.docx");
        verify(fileVersionRepository).deleteById(1L);
        verify(teamAuditService).record(10L, 2L, "VERSION_DELETE", "FILE", 1L, "version 1");
    }

    @Test
    void restoreVersion_shouldArchiveCurrentAndUploadSelectedVersion() {
        FileEntity file = ownedFile(1L, 1L);
        FileVersionEntity selected = version(1, "users/1/v1.txt");
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(file));
        when(fileVersionRepository.findByFileIdAndVersionNumber(1L, 1)).thenReturn(Optional.of(selected));
        when(fileVersionRepository.findMaxVersionNumberByFileId(1L)).thenReturn(2, 3);
        when(fileVersionRepository.save(any(FileVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(storageService.downloadObject("users/1/v1.txt"))
                .thenReturn(new ByteArrayInputStream("version-one".getBytes()));

        FileEntity restored = fileVersionService.restoreVersion(1L, 1L, 1);

        assertThat(restored.getStoragePath()).startsWith("users/1/versions/restore/1/");
        assertThat(restored.getFileSize()).isEqualTo(10L);
        assertThat(restored.getMd5Hash()).isEqualTo("md5-v1");
        assertThat(restored.getVersionCount()).isEqualTo(4);
        verify(storageService).uploadObject(startsWith("users/1/versions/restore/1/"), any(), eq(10L), eq("application/octet-stream"));
    }

    @Test
    void restoreTeamVersion_shouldUseTeamRestorePath() {
        FileEntity file = teamFile(1L, 2L, 10L);
        FileVersionEntity selected = version(1, "teams/10/v1.docx");
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(file));
        when(fileVersionRepository.findByFileIdAndVersionNumber(1L, 1)).thenReturn(Optional.of(selected));
        when(fileVersionRepository.findMaxVersionNumberByFileId(1L)).thenReturn(1, 2);
        when(fileVersionRepository.save(any(FileVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(storageService.downloadObject("teams/10/v1.docx"))
                .thenReturn(new ByteArrayInputStream("team-version-one".getBytes()));

        FileEntity restored = fileVersionService.restoreTeamVersion(10L, 2L, 1L, 1);

        assertThat(restored.getStoragePath()).startsWith("teams/10/versions/restore/1/");
        verify(storageService).uploadObject(
                startsWith("teams/10/versions/restore/1/"),
                any(),
                eq(10L),
                eq("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        verify(teamAuditService).record(10L, 2L, "VERSION_RESTORE", "FILE", 1L, "version 1");
        verify(valueOps).increment("team:storage:incr:10", 10L);
    }

    @Test
    void listTeamVersions_shouldRejectAFileUnderADeletedAncestor() {
        FileEntity file = teamFile(1L, 2L, 10L);
        file.setParentId(9L);
        FileEntity deletedParent = FileEntity.builder().id(9L).teamId(10L).fileType("folder").isDeleted(true).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.findById(9L)).thenReturn(Optional.of(deletedParent));

        assertThatThrownBy(() -> fileVersionService.listTeamVersions(10L, 1L))
                .isInstanceOf(BusinessException.class);

        verify(fileVersionRepository, never()).findTopVersionsByFileId(anyLong(), any(PageRequest.class));
    }

    @Test
    void restoreTeamVersion_shouldRejectWhenTeamQuotaWouldBeExceeded() {
        FileEntity file = teamFile(1L, 2L, 10L);
        FileVersionEntity selected = version(1, "teams/10/v1.docx");
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(file));
        when(fileVersionRepository.findByFileIdAndVersionNumber(1L, 1)).thenReturn(Optional.of(selected));
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(
                TeamEntity.builder().id(10L).storageUsed(95L).storageQuota(100L).build()));
        when(valueOps.get("team:storage:incr:10")).thenReturn(null);

        assertThatThrownBy(() -> fileVersionService.restoreTeamVersion(10L, 2L, 1L, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419001));

        verify(storageService, never()).downloadObject(anyString());
        verify(fileVersionRepository, never()).save(any(FileVersionEntity.class));
    }
}
