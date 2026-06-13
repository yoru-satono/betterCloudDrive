package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.ShareLinkEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.ShareLinkRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class ShareServiceImplTest {

    @Mock private ShareLinkRepository shareLinkRepository;
    @Mock private FileRepository fileRepository;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ZSetOperations<String, String> zSetOps;
    @InjectMocks private ShareServiceImpl shareService;

    private FileEntity ownedFile(Long id, Long userId) {
        return FileEntity.builder().id(id).userId(userId).fileType("file").isDeleted(false).build();
    }

    private FileEntity ownedFolder(Long id, Long userId) {
        return FileEntity.builder().id(id).userId(userId).fileType("folder").isDeleted(false).build();
    }

    private ShareLinkEntity activeShare(String code, Long userId, Long fileId) {
        return ShareLinkEntity.builder()
                .id(1L).userId(userId).fileId(fileId).shareCode(code)
                .isCanceled(false).downloadCount(0).visitCount(0).build();
    }

    private UserEntity quotaUser(Long id, long quota, long used) {
        return UserEntity.builder().id(id).storageQuota(quota).storageUsed(used).build();
    }

    @Test
    void createShare_shouldGenerateShareCode() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));
        when(shareLinkRepository.save(any(ShareLinkEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ShareLinkEntity share = shareService.createShare(1L, 1L, null, null, null);

        assertThat(share.getShareCode()).isNotBlank().hasSize(8);
        assertThat(share.getPasswordHash()).isNull();
    }

    @Test
    void createShare_shouldEncodePasswordWhenProvided() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));
        when(passwordEncoder.encode("secret")).thenReturn("encoded-secret");
        when(shareLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShareLinkEntity share = shareService.createShare(1L, 1L, "secret", null, null);

        assertThat(share.getPasswordHash()).isEqualTo("encoded-secret");
    }

    @Test
    void createShare_shouldTreatBlankPasswordAsNoPassword() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));
        when(shareLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShareLinkEntity share = shareService.createShare(1L, 1L, "   ", null, null);

        assertThat(share.getPasswordHash()).isNull();
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void createShare_shouldRejectPasswordShorterThanFourCharacters() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));

        assertThatThrownBy(() -> shareService.createShare(1L, 1L, "abc", null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
        verify(shareLinkRepository, never()).save(any());
    }

    @Test
    void createShare_shouldRejectPasswordLongerThanSixteenCharacters() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));

        assertThatThrownBy(() -> shareService.createShare(1L, 1L, "12345678901234567", null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
        verify(shareLinkRepository, never()).save(any());
    }

    @Test
    void updateShare_shouldRemovePasswordWhenBlank() {
        ShareLinkEntity share = activeShare("abcd1234", 1L, 1L);
        share.setPasswordHash("existing-hash");
        when(shareLinkRepository.findById(1L)).thenReturn(Optional.of(share));
        when(shareLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShareLinkEntity updated = shareService.updateShare(1L, 1L, "  ", null, null);

        assertThat(updated.getPasswordHash()).isNull();
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void updateShare_shouldRejectPasswordOutsideAllowedLength() {
        ShareLinkEntity share = activeShare("abcd1234", 1L, 1L);
        when(shareLinkRepository.findById(1L)).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> shareService.updateShare(1L, 1L, "abc", null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
        verify(shareLinkRepository, never()).save(any());
    }

    @Test
    void createShare_shouldThrowWhenFileNotFound() {
        when(fileRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shareService.createShare(1L, 99L, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createShare_shouldThrowWhenFileNotOwned() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 2L)));

        assertThatThrownBy(() -> shareService.createShare(1L, 1L, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listShares_shouldReturnActiveSharingLinks() {
        ShareLinkEntity share = activeShare("abcd1234", 1L, 1L);
        Page<ShareLinkEntity> page = new PageImpl<>(List.of(share), PageRequest.of(0, 20), 1);
        when(shareLinkRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        PageResult<ShareLinkEntity> result = shareService.listShares(1L, 1, 20);

        assertThat(result.getRecords()).hasSize(1);
    }

    @Test
    void cancelShare_shouldMarkAsCanceled() {
        ShareLinkEntity share = activeShare("abcd1234", 1L, 1L);
        when(shareLinkRepository.findById(1L)).thenReturn(Optional.of(share));
        when(shareLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        shareService.cancelShare(1L, 1L);

        assertThat(share.getIsCanceled()).isTrue();
    }

    @Test
    void accessShare_shouldReturnFileWhenValid() {
        ShareLinkEntity share = activeShare("code1234", 1L, 10L);
        FileEntity file = ownedFile(10L, 1L);
        when(shareLinkRepository.findByShareCode("code1234")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(file));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        FileEntity result = shareService.accessShare("code1234", null);

        assertThat(result).isSameAs(file);
        verify(zSetOps).incrementScore("share:visits", "code1234", 1);
    }

    @Test
    void accessShare_shouldThrowWhenShareCanceled() {
        ShareLinkEntity share = activeShare("code1234", 1L, 10L);
        share.setIsCanceled(true);
        when(shareLinkRepository.findByShareCode("code1234")).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> shareService.accessShare("code1234", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void accessShare_shouldThrowWhenShareExpired() {
        ShareLinkEntity share = activeShare("code5678", 1L, 10L);
        share.setExpireAt(LocalDateTime.now().minusHours(1));
        when(shareLinkRepository.findByShareCode("code5678")).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> shareService.accessShare("code5678", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void accessShare_shouldThrowWhenPasswordWrong() {
        ShareLinkEntity share = activeShare("code9999", 1L, 10L);
        share.setPasswordHash("encoded-pw");
        when(shareLinkRepository.findByShareCode("code9999")).thenReturn(Optional.of(share));
        when(passwordEncoder.matches("wrong", "encoded-pw")).thenReturn(false);

        assertThatThrownBy(() -> shareService.accessShare("code9999", "wrong"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void accessShare_shouldThrowWhenVisitLimitExceeded() {
        ShareLinkEntity share = activeShare("codeABCD", 1L, 10L);
        share.setMaxVisits(3);
        share.setVisitCount(2);
        when(shareLinkRepository.findByShareCode("codeABCD")).thenReturn(Optional.of(share));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.score("share:visits", "codeABCD")).thenReturn(1D);

        assertThatThrownBy(() -> shareService.accessShare("codeABCD", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419005));
        verify(zSetOps, never()).incrementScore(anyString(), anyString(), anyDouble());
    }

    @Test
    void listSharedFiles_shouldReturnSingleFileForFileType() {
        ShareLinkEntity share = activeShare("codeXYZ", 1L, 5L);
        FileEntity sharedFile = ownedFile(5L, 1L);
        when(shareLinkRepository.findByShareCode("codeXYZ")).thenReturn(Optional.of(share));
        when(fileRepository.findById(5L)).thenReturn(Optional.of(sharedFile));

        PageResult<FileEntity> result = shareService.listSharedFiles("codeXYZ", null, 1, 20);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getId()).isEqualTo(5L);
    }

    @Test
    void downloadSharedFile_shouldReturnSharedFileAndIncrementDownloadCount() {
        ShareLinkEntity share = activeShare("download", 1L, 5L);
        FileEntity sharedFile = ownedFile(5L, 1L);
        sharedFile.setStoragePath("objects/5");
        when(shareLinkRepository.findByShareCode("download")).thenReturn(Optional.of(share));
        when(fileRepository.findById(5L)).thenReturn(Optional.of(sharedFile));

        FileEntity result = shareService.downloadSharedFile("download", 5L, null);

        assertThat(result).isSameAs(sharedFile);
        verify(shareLinkRepository).incrementDownloadCount(share.getId());
    }

    @Test
    void downloadSharedFile_shouldAllowFileInsideSharedFolder() {
        ShareLinkEntity share = activeShare("folder1", 1L, 10L);
        FileEntity folder = ownedFolder(10L, 1L);
        FileEntity child = ownedFile(11L, 1L);
        child.setParentId(10L);
        when(shareLinkRepository.findByShareCode("folder1")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(folder));
        when(fileRepository.findById(11L)).thenReturn(Optional.of(child));

        FileEntity result = shareService.downloadSharedFile("folder1", 11L, null);

        assertThat(result).isSameAs(child);
        verify(shareLinkRepository).incrementDownloadCount(share.getId());
    }

    @Test
    void downloadSharedFile_shouldRejectFileOutsideSharedFolder() {
        ShareLinkEntity share = activeShare("folder2", 1L, 10L);
        FileEntity folder = ownedFolder(10L, 1L);
        FileEntity outside = ownedFile(12L, 1L);
        outside.setParentId(null);
        when(shareLinkRepository.findByShareCode("folder2")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(folder));
        when(fileRepository.findById(12L)).thenReturn(Optional.of(outside));

        assertThatThrownBy(() -> shareService.downloadSharedFile("folder2", 12L, null))
                .isInstanceOf(BusinessException.class);
        verify(shareLinkRepository, never()).incrementDownloadCount(anyLong());
    }

    @Test
    void resolveSharedFolderDownload_shouldReturnFolderInsideSharedScopeWithoutIncrementingCount() {
        ShareLinkEntity share = activeShare("zipfolder", 1L, 10L);
        FileEntity root = ownedFolder(10L, 1L);
        FileEntity childFolder = ownedFolder(11L, 1L);
        childFolder.setParentId(10L);
        when(shareLinkRepository.findByShareCode("zipfolder")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(root));
        when(fileRepository.findById(11L)).thenReturn(Optional.of(childFolder));

        FileEntity result = shareService.resolveSharedFolderDownload("zipfolder", 11L, null);

        assertThat(result).isSameAs(childFolder);
        verify(shareLinkRepository, never()).incrementDownloadCount(anyLong());
    }

    @Test
    void resolveSharedFolderDownload_shouldRejectFilesAndItemsOutsideScope() {
        ShareLinkEntity share = activeShare("zipreject", 1L, 10L);
        FileEntity root = ownedFolder(10L, 1L);
        FileEntity file = ownedFile(11L, 1L);
        file.setParentId(10L);
        when(shareLinkRepository.findByShareCode("zipreject")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(root));
        when(fileRepository.findById(11L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> shareService.resolveSharedFolderDownload("zipreject", 11L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
        verify(shareLinkRepository, never()).incrementDownloadCount(anyLong());
    }

    @Test
    void recordSharedDownload_shouldValidatePasswordAndIncrementCount() {
        ShareLinkEntity share = activeShare("zipcount", 1L, 10L);
        share.setPasswordHash("encoded");
        when(shareLinkRepository.findByShareCode("zipcount")).thenReturn(Optional.of(share));
        when(passwordEncoder.matches("pw1234", "encoded")).thenReturn(true);

        shareService.recordSharedDownload("zipcount", "pw1234");

        verify(shareLinkRepository).incrementDownloadCount(share.getId());
    }

    @Test
    void saveSharedItem_shouldSaveRootFileToTargetFolderAndIncrementCounters() {
        ShareLinkEntity share = activeShare("savefile", 1L, 10L);
        FileEntity source = ownedFile(10L, 1L);
        source.setFileName("report.pdf");
        source.setFileSize(256L);
        source.setStoragePath("objects/report");
        source.setMd5Hash("md5-report");
        FileEntity targetFolder = ownedFolder(20L, 2L);

        when(shareLinkRepository.findByShareCode("savefile")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(source));
        when(fileRepository.findById(20L)).thenReturn(Optional.of(targetFolder));
        when(fileRepository.existsByUserIdAndParentIdAndFileNameAndIsDeletedFalse(2L, 20L, "report.pdf")).thenReturn(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(quotaUser(2L, 1024L, 100L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:2")).thenReturn("10");
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> {
            FileEntity saved = inv.getArgument(0);
            saved.setId(100L);
            return saved;
        });

        FileEntity saved = shareService.saveSharedItem("savefile", null, 20L, 2L, null);

        assertThat(saved.getId()).isEqualTo(100L);
        assertThat(saved.getUserId()).isEqualTo(2L);
        assertThat(saved.getParentId()).isEqualTo(20L);
        assertThat(saved.getFileName()).isEqualTo("report.pdf");
        assertThat(saved.getStoragePath()).isEqualTo("objects/report");
        verify(valueOps).increment("storage:incr:2", 256L);
        verify(shareLinkRepository).incrementDownloadCount(share.getId());
    }

    @Test
    void saveSharedItem_shouldRecursivelySaveSharedFolder() {
        ShareLinkEntity share = activeShare("savefolder", 1L, 10L);
        FileEntity root = ownedFolder(10L, 1L);
        root.setFileName("Shared");
        FileEntity childFolder = ownedFolder(11L, 1L);
        childFolder.setParentId(10L);
        childFolder.setFileName("Docs");
        FileEntity childFile = ownedFile(12L, 1L);
        childFile.setParentId(10L);
        childFile.setFileName("root.txt");
        childFile.setFileSize(100L);
        childFile.setStoragePath("objects/root");
        FileEntity nestedFile = ownedFile(13L, 1L);
        nestedFile.setParentId(11L);
        nestedFile.setFileName("nested.txt");
        nestedFile.setFileSize(50L);
        nestedFile.setStoragePath("objects/nested");

        when(shareLinkRepository.findByShareCode("savefolder")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(root));
        when(fileRepository.existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(2L, "Shared")).thenReturn(false);
        when(fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(1L, 10L))
                .thenReturn(List.of(childFolder, childFile));
        when(fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(1L, 11L))
                .thenReturn(List.of(nestedFile));
        when(userRepository.findById(2L)).thenReturn(Optional.of(quotaUser(2L, 1024L, 0L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:2")).thenReturn(null);
        final long[] nextId = {100L};
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> {
            FileEntity saved = inv.getArgument(0);
            saved.setId(nextId[0]++);
            return saved;
        });

        FileEntity savedRoot = shareService.saveSharedItem("savefolder", null, null, 2L, null);

        assertThat(savedRoot.getId()).isEqualTo(100L);
        org.mockito.ArgumentCaptor<FileEntity> captor = org.mockito.ArgumentCaptor.forClass(FileEntity.class);
        verify(fileRepository, times(4)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(FileEntity::getFileName, FileEntity::getUserId, FileEntity::getParentId)
                .containsExactly(
                        tuple("Shared", 2L, null),
                        tuple("Docs", 2L, 100L),
                        tuple("nested.txt", 2L, 101L),
                        tuple("root.txt", 2L, 100L)
                );
        verify(valueOps).increment("storage:incr:2", 150L);
        verify(shareLinkRepository).incrementDownloadCount(share.getId());
    }

    @Test
    void saveSharedItem_shouldRejectFolderSavedIntoItselfOrChild() {
        ShareLinkEntity share = activeShare("loop", 1L, 10L);
        FileEntity root = ownedFolder(10L, 1L);
        root.setFileName("Shared");
        FileEntity childFolder = ownedFolder(11L, 1L);
        childFolder.setParentId(10L);
        childFolder.setFileName("Child");

        when(shareLinkRepository.findByShareCode("loop")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(root));
        when(fileRepository.findById(11L)).thenReturn(Optional.of(childFolder));

        assertThatThrownBy(() -> shareService.saveSharedItem("loop", null, 11L, 1L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
        verify(fileRepository, never()).save(any(FileEntity.class));
        verify(shareLinkRepository, never()).incrementDownloadCount(anyLong());
    }

    @Test
    void saveSharedItem_shouldRejectInvalidTargetFolder() {
        ShareLinkEntity share = activeShare("badtarget", 1L, 10L);
        FileEntity source = ownedFile(10L, 1L);
        source.setFileName("file.txt");
        FileEntity targetFile = ownedFile(20L, 2L);

        when(shareLinkRepository.findByShareCode("badtarget")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(source));
        when(fileRepository.findById(20L)).thenReturn(Optional.of(targetFile));

        assertThatThrownBy(() -> shareService.saveSharedItem("badtarget", null, 20L, 2L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(404001));
        verify(fileRepository, never()).save(any(FileEntity.class));
    }

    @Test
    void saveSharedItem_shouldRejectQuotaExceeded() {
        ShareLinkEntity share = activeShare("quota", 1L, 10L);
        FileEntity source = ownedFile(10L, 1L);
        source.setFileName("big.bin");
        source.setFileSize(900L);

        when(shareLinkRepository.findByShareCode("quota")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(source));
        when(fileRepository.existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(2L, "big.bin")).thenReturn(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(quotaUser(2L, 1000L, 200L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:2")).thenReturn(null);

        assertThatThrownBy(() -> shareService.saveSharedItem("quota", null, null, 2L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419001));
        verify(fileRepository, never()).save(any(FileEntity.class));
        verify(shareLinkRepository, never()).incrementDownloadCount(anyLong());
    }

    @Test
    void saveSharedItem_shouldRejectNameConflict() {
        ShareLinkEntity share = activeShare("conflict", 1L, 10L);
        FileEntity source = ownedFile(10L, 1L);
        source.setFileName("same.txt");

        when(shareLinkRepository.findByShareCode("conflict")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(source));
        when(fileRepository.existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(2L, "same.txt")).thenReturn(true);

        assertThatThrownBy(() -> shareService.saveSharedItem("conflict", null, null, 2L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409001));
        verify(fileRepository, never()).save(any(FileEntity.class));
    }

    @Test
    void saveSharedItem_shouldRejectItemOutsideSharedScope() {
        ShareLinkEntity share = activeShare("scope", 1L, 10L);
        FileEntity root = ownedFolder(10L, 1L);
        FileEntity outside = ownedFile(30L, 1L);
        outside.setParentId(null);

        when(shareLinkRepository.findByShareCode("scope")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(root));
        when(fileRepository.findById(30L)).thenReturn(Optional.of(outside));

        assertThatThrownBy(() -> shareService.saveSharedItem("scope", 30L, null, 2L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(404001));
        verify(fileRepository, never()).save(any(FileEntity.class));
    }

    @Test
    void saveSharedItem_shouldIgnoreVisitLimitAndIncrementDownloadCount() {
        ShareLinkEntity share = activeShare("limit", 1L, 10L);
        share.setMaxVisits(1);
        share.setVisitCount(1);
        FileEntity source = ownedFile(10L, 1L);
        source.setFileName("limited.txt");
        source.setFileSize(10L);
        when(shareLinkRepository.findByShareCode("limit")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(source));
        when(fileRepository.existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(2L, "limited.txt")).thenReturn(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(quotaUser(2L, 100L, 0L)));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:2")).thenReturn(null);
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> {
            FileEntity saved = inv.getArgument(0);
            saved.setId(100L);
            return saved;
        });

        FileEntity saved = shareService.saveSharedItem("limit", null, null, 2L, null);

        assertThat(saved.getFileName()).isEqualTo("limited.txt");
        verify(shareLinkRepository).incrementDownloadCount(share.getId());
    }
}
