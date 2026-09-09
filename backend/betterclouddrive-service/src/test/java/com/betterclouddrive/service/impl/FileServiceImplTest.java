package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FileVersionRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.dto.TeamEffectivePermission;
import com.betterclouddrive.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    @Mock
    private FileRepository fileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StorageService storageService;

    @Mock
    private FileVersionRepository fileVersionRepository;

    @Mock
    private TeamAuditService teamAuditService;

    @Mock
    private TeamPermissionService teamPermissionService;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private FileServiceImpl fileService;

    @BeforeEach
    void allowPersonalMutationLock() {
        lenient().when(userRepository.findByIdForUpdate(anyLong()))
                .thenReturn(Optional.of(UserEntity.builder().id(1L).build()));
        lenient().when(teamRepository.findActiveByIdForUpdate(anyLong()))
                .thenReturn(Optional.of(TeamEntity.builder().id(10L).build()));
    }

    @Test
    void listFiles_shouldReturnPage() {
        FileEntity file1 = FileEntity.builder().id(1L).fileName("a.txt").build();
        FileEntity file2 = FileEntity.builder().id(2L).fileName("b.txt").build();
        Page<FileEntity> page = new PageImpl<>(List.of(file1, file2), PageRequest.of(0, 20), 2);
        when(fileRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        PageResult<FileEntity> result = fileService.listFiles(1L, null, 1, 20, "fileName", "asc");

        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(2);
    }

    @Test
    void listFiles_shouldUseStableFolderFirstRequestedSort() {
        when(fileRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 50), 120));

        fileService.listFiles(1L, 9L, 2, 50, "updatedAt", "desc");

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(fileRepository).findAll(any(Specification.class), captor.capture());
        PageRequest request = captor.getValue();
        assertThat(request.getPageNumber()).isEqualTo(1);
        assertThat(request.getPageSize()).isEqualTo(50);
        assertThat(request.getSort().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple("fileType", Sort.Direction.DESC),
                        tuple("updatedAt", Sort.Direction.DESC),
                        tuple("fileName", Sort.Direction.ASC),
                        tuple("id", Sort.Direction.ASC));
        assertThat(request.getSort().getOrderFor("updatedAt").getNullHandling())
                .isEqualTo(Sort.NullHandling.NULLS_LAST);
    }

    @Test
    void listFiles_shouldFallBackToNameAscendingForUnknownSortParameters() {
        when(fileRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(Page.empty());

        fileService.listFiles(1L, null, 1, 20, null, null);

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(fileRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getSort().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple("fileType", Sort.Direction.DESC),
                        tuple("fileName", Sort.Direction.ASC),
                        tuple("id", Sort.Direction.ASC));
    }

    @Test
    void getFile_shouldReturnFile() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        FileEntity result = fileService.getFile(1L, 1L);

        assertThat(result).isSameAs(file);
    }

    @Test
    void getFile_shouldThrowWhenNotOwner() {
        FileEntity file = FileEntity.builder().id(1L).userId(2L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> fileService.getFile(1L, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getFile_shouldThrowWhenDeleted() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).isDeleted(true).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> fileService.getFile(1L, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listTeamFiles_shouldPageAfterPermissionFiltering() {
        FileEntity hidden = FileEntity.builder().id(1L).teamId(10L).fileName("hidden.txt").fileType("file").build();
        FileEntity visibleOne = FileEntity.builder().id(2L).teamId(10L).fileName("visible-a.txt").fileType("file").build();
        FileEntity visibleTwo = FileEntity.builder().id(3L).teamId(10L).fileName("visible-b.txt").fileType("file").build();
        when(fileRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(hidden, visibleOne, visibleTwo));
        when(teamPermissionService.effectivePermissions(7L, 10L, 1L)).thenReturn(new TeamEffectivePermission(false, false, false, false, false));
        when(teamPermissionService.effectivePermissions(7L, 10L, 2L)).thenReturn(TeamEffectivePermission.viewer());
        when(teamPermissionService.effectivePermissions(7L, 10L, 3L)).thenReturn(TeamEffectivePermission.viewer());

        PageResult<FileEntity> result = fileService.listTeamFiles(7L, 10L, null, 1, 1, "fileName", "asc");

        assertThat(result.getRecords()).extracting(FileEntity::getId).containsExactly(2L);
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getPages()).isEqualTo(2);
    }

    @Test
    void listTeamFiles_shouldReturnSecondVisiblePageAfterPermissionFiltering() {
        FileEntity hidden = FileEntity.builder().id(1L).teamId(10L).fileName("hidden.txt").fileType("file").build();
        FileEntity visibleOne = FileEntity.builder().id(2L).teamId(10L).fileName("visible-a.txt").fileType("file").build();
        FileEntity visibleTwo = FileEntity.builder().id(3L).teamId(10L).fileName("visible-b.txt").fileType("file").build();
        when(fileRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(hidden, visibleOne, visibleTwo));
        when(teamPermissionService.effectivePermissions(7L, 10L, 1L)).thenReturn(new TeamEffectivePermission(false, false, false, false, false));
        when(teamPermissionService.effectivePermissions(7L, 10L, 2L)).thenReturn(TeamEffectivePermission.viewer());
        when(teamPermissionService.effectivePermissions(7L, 10L, 3L)).thenReturn(TeamEffectivePermission.viewer());

        PageResult<FileEntity> result = fileService.listTeamFiles(7L, 10L, null, 2, 1, "fileName", "asc");

        assertThat(result.getRecords()).extracting(FileEntity::getId).containsExactly(3L);
        assertThat(result.getTotal()).isEqualTo(2);
    }

    @Test
    void listTeamFiles_shouldUseRequestedStableFolderFirstSortBeforePermissionFiltering() {
        when(fileRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        fileService.listTeamFiles(7L, 10L, null, 1, 50, "fileSize", "desc");

        ArgumentCaptor<Sort> captor = ArgumentCaptor.forClass(Sort.class);
        verify(fileRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple("fileType", Sort.Direction.DESC),
                        tuple("fileSize", Sort.Direction.DESC),
                        tuple("fileName", Sort.Direction.ASC),
                        tuple("id", Sort.Direction.ASC));
    }

    @Test
    void searchTeamFiles_shouldTrimAndUseStableFolderFirstOrder() {
        FileEntity visible = FileEntity.builder().id(2L).teamId(10L).fileName("notes.txt").fileType("file").build();
        when(fileRepository.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(visible));
        when(teamPermissionService.effectivePermissions(7L, 10L, 2L)).thenReturn(TeamEffectivePermission.viewer());

        PageResult<FileEntity> result = fileService.searchTeamFiles(7L, 10L, " notes ", 1, 50);

        assertThat(result.getRecords()).containsExactly(visible);
        ArgumentCaptor<Sort> captor = ArgumentCaptor.forClass(Sort.class);
        verify(fileRepository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple("fileType", Sort.Direction.DESC),
                        tuple("fileName", Sort.Direction.ASC),
                        tuple("id", Sort.Direction.ASC));
    }

    @Test
    void searchTeamFiles_shouldReturnEmptyPageForBlankQueryWithoutScanningFiles() {
        PageResult<FileEntity> result = fileService.searchTeamFiles(7L, 10L, "   ", 2, 50);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getPage()).isEqualTo(2);
        verify(fileRepository, never()).findAll(any(Specification.class), any(Sort.class));
    }

    @Test
    void getFile_shouldThrowForTeamFile() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).teamId(10L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> fileService.getFile(1L, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createFolder_shouldSucceed() {
        FileEntity saved = FileEntity.builder().id(10L).fileName("NewFolder").build();
        when(fileRepository.save(any(FileEntity.class))).thenReturn(saved);

        fileService.createFolder(1L, null, "  NewFolder  ");

        ArgumentCaptor<FileEntity> captor = ArgumentCaptor.forClass(FileEntity.class);
        verify(fileRepository).save(captor.capture());
        assertThat(captor.getValue().getFileName()).isEqualTo("NewFolder");
    }

    @Test
    void createFolder_shouldThrowWhenNameConflict() {
        when(fileRepository.existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(1L, "NewFolder"))
                .thenReturn(true);

        assertThatThrownBy(() -> fileService.createFolder(1L, null, "NewFolder"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createFolder_shouldValidateParentOwnershipAndType() {
        FileEntity foreignFile = FileEntity.builder().id(9L).userId(2L).fileType("file").isDeleted(false).build();
        when(fileRepository.findById(9L)).thenReturn(Optional.of(foreignFile));

        assertThatThrownBy(() -> fileService.createFolder(1L, 9L, "Docs"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(404001));
        verify(fileRepository, never()).save(any());
    }

    @Test
    void createFolder_shouldRejectBlankAfterTrimAndOverlongNames() {
        assertThatThrownBy(() -> fileService.createFolder(1L, null, "   "))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getMessageKey()).isEqualTo("error.invalidFileName"));
        assertThatThrownBy(() -> fileService.createFolder(1L, null, "a".repeat(256)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getMessageKey()).isEqualTo("error.invalidFileName"));
    }

    @Test
    void createTeamFolder_shouldSetTeamIdAndCreator() {
        when(fileRepository.existsByTeamIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(10L, "Docs")).thenReturn(false);
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        FileEntity folder = fileService.createTeamFolder(10L, 2L, null, "Docs");

        assertThat(folder.getTeamId()).isEqualTo(10L);
        assertThat(folder.getUserId()).isEqualTo(2L);
        assertThat(folder.getFileType()).isEqualTo("folder");
    }

    @Test
    void createTeamFolder_shouldTrimAndValidateName() {
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        FileEntity folder = fileService.createTeamFolder(10L, 2L, null, "  Docs  ");
        assertThat(folder.getFileName()).isEqualTo("Docs");
        assertThatThrownBy(() -> fileService.createTeamFolder(10L, 2L, null, "   "))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getMessageKey()).isEqualTo("error.invalidFileName"));
    }

    @Test
    void renameTeamFile_shouldBeIdempotentAfterNormalization() {
        FileEntity file = FileEntity.builder().id(1L).teamId(10L).fileName("old.txt").isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        FileEntity unchanged = fileService.renameTeamFile(10L, 2L, 1L, " old.txt ");
        assertThat(unchanged).isSameAs(file);
        verify(fileRepository, never()).save(any());
        verify(teamAuditService, never()).record(eq(10L), eq(2L), eq("FILE_RENAME"), any(), any(), any());
    }

    @Test
    void renameFile_shouldSucceed() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).fileName("old.txt").isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);

        fileService.renameFile(1L, 1L, "  NewName  ");

        assertThat(file.getFileName()).isEqualTo("NewName");
    }

    @Test
    void renameFile_shouldBeIdempotentAndRejectSharedNamespaceConflict() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).fileName("old.txt").isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        FileEntity unchanged = fileService.renameFile(1L, 1L, " old.txt ");
        assertThat(unchanged).isSameAs(file);
        verify(fileRepository, never()).save(any());

        when(fileRepository.existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(1L, "folder"))
                .thenReturn(true);
        assertThatThrownBy(() -> fileService.renameFile(1L, 1L, "folder"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409001));
    }

    @Test
    void moveFile_shouldSucceed() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).parentId(50L).isDeleted(false).build();
        FileEntity target = FileEntity.builder().id(100L).userId(1L).fileType("folder").isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.findById(100L)).thenReturn(Optional.of(target));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);

        fileService.moveFile(1L, 1L, 100L);

        assertThat(file.getParentId()).isEqualTo(100L);
    }

    @Test
    void moveFile_shouldBeIdempotentForCurrentParent() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).parentId(50L).fileName("a.txt").isDeleted(false).build();
        FileEntity target = FileEntity.builder().id(50L).userId(1L).fileType("folder").isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.findById(50L)).thenReturn(Optional.of(target));

        fileService.moveFile(1L, 1L, 50L);

        verify(fileRepository, never()).save(any());
    }

    @Test
    void copyFile_shouldAppendCopySuffix() {
        FileEntity source = FileEntity.builder()
                .id(1L).userId(1L).fileName("doc.txt")
                .fileType("file").isDeleted(false).build();
        FileEntity target = FileEntity.builder().id(100L).userId(1L).fileType("folder").isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(source));
        when(fileRepository.findById(100L)).thenReturn(Optional.of(target));
        ArgumentCaptor<FileEntity> captor = ArgumentCaptor.forClass(FileEntity.class);
        when(fileRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        fileService.copyFile(1L, 1L, 100L);

        assertThat(captor.getValue().getFileName()).isEqualTo("doc.txt (copy)");
        assertThat(captor.getValue().getParentId()).isEqualTo(100L);
    }

    @Test
    void copyFile_shouldTruncateLongNameBeforeSuffix() {
        String longName = "x".repeat(255);
        FileEntity source = FileEntity.builder().id(1L).userId(1L).fileName(longName)
                .fileType("file").fileSize(0L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(source));
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        fileService.copyFile(1L, 1L, null);

        ArgumentCaptor<FileEntity> captor = ArgumentCaptor.forClass(FileEntity.class);
        verify(fileRepository).save(captor.capture());
        assertThat(captor.getValue().getFileName()).hasSize(255).endsWith(" (copy)");
    }

    @Test
    void copyFileTo_shouldCopyFolderRecursivelyAndIncrementStorage() {
        FileEntity folder = FileEntity.builder()
                .id(1L).userId(1L).fileName("Docs").fileType("folder")
                .fileSize(0L).isDeleted(false).build();
        FileEntity child = FileEntity.builder()
                .id(2L).userId(1L).parentId(1L).fileName("a.txt")
                .fileType("file").fileSize(64L).storagePath("objects/a.txt")
                .md5Hash("md5").isDeleted(false).build();
        List<FileEntity> saved = new ArrayList<>();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(folder));
        when(fileRepository.existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(1L, "Docs copy"))
                .thenReturn(false);
        when(fileRepository.findAll(any(Specification.class))).thenReturn(List.of(child), List.of(child));
        when(userRepository.findById(1L))
                .thenReturn(Optional.of(UserEntity.builder().id(1L).storageQuota(1024L).storageUsed(100L).build()));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> {
            FileEntity file = inv.getArgument(0);
            file.setId(saved.isEmpty() ? 100L : 101L);
            saved.add(file);
            return file;
        });

        FileEntity copied = fileService.copyFileTo(1L, 1L, null, "Docs copy");

        assertThat(copied.getId()).isEqualTo(100L);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getFileName()).isEqualTo("Docs copy");
        assertThat(saved.get(0).getFileType()).isEqualTo("folder");
        assertThat(saved.get(1).getFileName()).isEqualTo("a.txt");
        assertThat(saved.get(1).getParentId()).isEqualTo(100L);
        assertThat(saved.get(1).getStoragePath()).isEqualTo("objects/a.txt");
        verify(valueOps).increment("storage:incr:1", 64L);
    }

    @Test
    void copyFileTo_shouldRejectNameConflict() {
        FileEntity source = FileEntity.builder()
                .id(1L).userId(1L).fileName("doc.txt")
                .fileType("file").fileSize(64L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(source));
        when(fileRepository.existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(1L, "doc.txt"))
                .thenReturn(true);

        assertThatThrownBy(() -> fileService.copyFileTo(1L, 1L, null, "doc.txt"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409001));
        verify(fileRepository, never()).save(any(FileEntity.class));
    }

    @Test
    void copyFileTo_shouldRejectFolderCopiedIntoDescendant() {
        FileEntity folder = FileEntity.builder()
                .id(1L).userId(1L).fileName("Docs")
                .fileType("folder").isDeleted(false).build();
        FileEntity child = FileEntity.builder()
                .id(2L).userId(1L).parentId(1L).fileName("Child")
                .fileType("folder").isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(folder), Optional.of(folder));
        when(fileRepository.findById(2L)).thenReturn(Optional.of(child));

        assertThatThrownBy(() -> fileService.copyFileTo(1L, 1L, 2L, "Docs"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(400));
        verify(fileRepository, never()).save(any(FileEntity.class));
    }

    @Test
    void copyFileTo_shouldRejectWhenQuotaExceeded() {
        FileEntity source = FileEntity.builder()
                .id(1L).userId(1L).fileName("big.bin")
                .fileType("file").fileSize(64L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(source));
        when(fileRepository.existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(1L, "big-copy.bin"))
                .thenReturn(false);
        when(userRepository.findById(1L))
                .thenReturn(Optional.of(UserEntity.builder().id(1L).storageQuota(120L).storageUsed(100L).build()));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("storage:incr:1")).thenReturn(null);

        assertThatThrownBy(() -> fileService.copyFileTo(1L, 1L, null, "big-copy.bin"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(419001));
        verify(fileRepository, never()).save(any(FileEntity.class));
    }

    @Test
    void moveFile_shouldAllowRootTarget() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).fileName("doc.txt").parentId(50L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(1L, "doc.txt")).thenReturn(false);
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);

        fileService.moveFile(1L, 1L, null);

        assertThat(file.getParentId()).isNull();
    }

    @Test
    void moveFile_shouldRejectFolderMovedIntoChild() {
        FileEntity folder = FileEntity.builder().id(1L).userId(1L).fileName("docs").fileType("folder").isDeleted(false).build();
        FileEntity child = FileEntity.builder().id(2L).userId(1L).parentId(1L).fileType("folder").isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(folder));
        when(fileRepository.findById(2L)).thenReturn(Optional.of(child));

        assertThatThrownBy(() -> fileService.moveFile(1L, 1L, 2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getCode()).isEqualTo(400);
                    assertThat(((BusinessException) ex).getMessageKey()).isEqualTo("error.invalidFileDestination");
                });
        verify(fileRepository, never()).save(any(FileEntity.class));
    }

    @Test
    void deleteFiles_shouldSoftDelete() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);

        fileService.deleteFiles(1L, List.of(1L));

        assertThat(file.getIsDeleted()).isTrue();
        assertThat(file.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteFiles_shouldDeduplicateIds() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);

        fileService.deleteFiles(1L, List.of(1L, 1L));

        verify(fileRepository, times(1)).findById(1L);
        verify(fileRepository, times(1)).save(file);
    }

    @Test
    void restoreFile_shouldClearDeletedAt() {
        LocalDateTime now = LocalDateTime.now();
        FileEntity file = FileEntity.builder()
                .id(1L).userId(1L).isDeleted(true).deletedAt(now).build();
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(file));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);

        fileService.restoreFile(1L, 1L);

        assertThat(file.getIsDeleted()).isFalse();
        assertThat(file.getDeletedAt()).isNull();
    }

    @Test
    void listRecycleBin_shouldReturnDeletedFiles() {
        FileEntity file = FileEntity.builder().id(1L).fileName("deleted.txt").build();
        Page<FileEntity> page = new PageImpl<>(List.of(file), PageRequest.of(0, 20), 1);
        when(fileRepository.findPersonalRecycleRoots(eq(1L), eq("deleted"), any(PageRequest.class))).thenReturn(page);

        PageResult<FileEntity> result = fileService.listRecycleBin(1L, "deleted", 1, 20);

        assertThat(result.getRecords()).isNotEmpty();
        assertThat(result.getRecords()).hasSize(1);
    }

    @Test
    void permanentDelete_shouldRemoveFromDb() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).isDeleted(true).build();
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(file));
        when(fileRepository.findAll(any(Specification.class))).thenReturn(List.of());
        when(fileVersionRepository.findByFileIdOrderByVersionNumberDesc(1L)).thenReturn(List.of());

        fileService.permanentDelete(1L, 1L);

        verify(fileRepository).deleteById(1L);
    }

    @Test
    void permanentDelete_shouldDeleteThumbnailObject() {
        FileEntity file = FileEntity.builder()
                .id(1L)
                .userId(1L)
                .fileType("file")
                .storagePath("objects/photo.png")
                .thumbnailPath("thumbnails/users/1/1.jpg")
                .isDeleted(true)
                .build();
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(file));
        when(fileRepository.findAll(any(Specification.class))).thenReturn(List.of());
        when(fileVersionRepository.findByFileIdOrderByVersionNumberDesc(1L)).thenReturn(List.of());

        fileService.permanentDelete(1L, 1L);

        verify(storageService).deleteObject("objects/photo.png");
        verify(storageService).deleteObject("thumbnails/users/1/1.jpg");
        verify(fileRepository).deleteById(1L);
    }

    @Test
    void emptyRecycleBin_shouldDeleteAllWithBulkQuery() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).isDeleted(true).build();
        when(fileRepository.findAll(any(Specification.class))).thenReturn(List.of(file), List.of());
        when(fileVersionRepository.findByFileIdOrderByVersionNumberDesc(1L)).thenReturn(List.of());

        fileService.emptyRecycleBin(1L);

        verify(fileRepository).deleteById(1L);
    }

    @Test
    void deleteFiles_shouldDecrementStorage() {
        FileEntity file = FileEntity.builder()
                .id(1L).userId(100L).fileType("file").fileSize(2048L)
                .isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        fileService.deleteFiles(100L, List.of(1L));

        verify(valueOps).increment("storage:incr:100", -2048L);
    }

    @Test
    void deleteTeamFiles_shouldDecrementTeamStorage() {
        FileEntity file = FileEntity.builder()
                .id(1L).userId(100L).teamId(10L).fileType("file").fileSize(2048L)
                .isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        fileService.deleteTeamFiles(10L, 2L, List.of(1L));

        assertThat(file.getIsDeleted()).isTrue();
        verify(valueOps).increment("team:storage:incr:10", -2048L);
        verify(teamPermissionService).requireDelete(2L, 10L, 1L);
    }

    @Test
    void deleteTeamFiles_shouldDeduplicateIdsBeforePermissionAndMutation() {
        FileEntity file = FileEntity.builder()
                .id(1L).userId(100L).teamId(10L).fileType("file").fileSize(0L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);

        fileService.deleteTeamFiles(10L, 2L, List.of(1L, 1L));

        verify(teamPermissionService, times(1)).requireDelete(2L, 10L, 1L);
        verify(fileRepository, times(1)).save(file);
    }

    @Test
    void restoreTeamFile_shouldClearDeletedAtAndRejectNameConflict() {
        FileEntity file = FileEntity.builder()
                .id(1L).teamId(10L).parentId(null).fileName("doc.txt")
                .isDeleted(true).deletedAt(LocalDateTime.now()).build();
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(file));
        when(fileRepository.existsByTeamIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(10L, "doc.txt"))
                .thenReturn(false);
        when(fileRepository.save(any(FileEntity.class))).thenReturn(file);

        fileService.restoreTeamFile(10L, 2L, 1L);

        assertThat(file.getIsDeleted()).isFalse();
        assertThat(file.getDeletedAt()).isNull();
    }

    @Test
    void restoreTeamFile_shouldRestoreOnlyTheMatchingDeletedTreeAndRestoreQuota() {
        LocalDateTime marker = LocalDateTime.now();
        FileEntity folder = FileEntity.builder()
                .id(1L).teamId(10L).fileName("docs").fileType("folder")
                .isDeleted(true).deletedAt(marker).build();
        FileEntity child = FileEntity.builder()
                .id(2L).teamId(10L).parentId(1L).fileName("report.pdf").fileType("file")
                .fileSize(64L).isDeleted(true).deletedAt(marker).build();
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(folder));
        when(fileRepository.existsByTeamIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(10L, "docs"))
                .thenReturn(false);
        when(fileRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(child), List.of(), List.of(child), List.of());
        when(fileVersionRepository.findByFileIdOrderByVersionNumberDesc(2L)).thenReturn(List.of());
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(
                TeamEntity.builder().id(10L).storageUsed(10L).storageQuota(1000L).build()));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("team:storage:incr:10")).thenReturn(null);
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        fileService.restoreTeamFile(10L, 2L, 1L);

        assertThat(folder.getIsDeleted()).isFalse();
        assertThat(child.getIsDeleted()).isFalse();
        verify(valueOps).increment("team:storage:incr:10", 64L);
        verify(teamAuditService).record(10L, 2L, "FILE_RESTORE", "FILE", 1L, "docs");
    }

    @Test
    void listTeamRecycleBin_shouldReturnDeletedFiles() {
        FileEntity file = FileEntity.builder().id(1L).teamId(10L).fileName("deleted.txt")
                .isDeleted(true).deletedAt(LocalDateTime.now()).build();
        when(fileRepository.findAll(any(Specification.class), any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(file));
        when(teamPermissionService.effectivePermissions(2L, 10L, 1L)).thenReturn(TeamEffectivePermission.viewer());

        PageResult<FileEntity> result = fileService.listTeamRecycleBin(2L, 10L, "deleted", 1, 20);

        assertThat(result.getRecords()).hasSize(1);
    }

    @Test
    void listTeamRecycleBin_shouldExposeOnlyReadableDeletionRootsInStableOrder() {
        LocalDateTime marker = LocalDateTime.now();
        FileEntity root = FileEntity.builder().id(3L).teamId(10L).fileName("root")
                .isDeleted(true).deletedAt(marker).build();
        FileEntity nested = FileEntity.builder().id(2L).teamId(10L).parentId(3L).fileName("nested")
                .isDeleted(true).deletedAt(marker).build();
        FileEntity hidden = FileEntity.builder().id(1L).teamId(10L).fileName("hidden")
                .isDeleted(true).deletedAt(marker.minusSeconds(1)).build();
        when(fileRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(root, nested, hidden));
        when(fileRepository.findById(3L)).thenReturn(Optional.of(root));
        when(teamPermissionService.effectivePermissions(2L, 10L, 3L)).thenReturn(TeamEffectivePermission.viewer());
        when(teamPermissionService.effectivePermissions(2L, 10L, 1L))
                .thenReturn(new TeamEffectivePermission(false, false, false, false, false));

        PageResult<FileEntity> result = fileService.listTeamRecycleBin(2L, 10L, null, 1, 50);

        assertThat(result.getRecords()).extracting(FileEntity::getId).containsExactly(3L);
        assertThat(result.getTotal()).isEqualTo(1);
        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(fileRepository).findAll(any(Specification.class), sort.capture());
        assertThat(sort.getValue().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple("deletedAt", Sort.Direction.DESC),
                        tuple("id", Sort.Direction.DESC));
    }

    @Test
    void permanentDeleteTeamFile_shouldAuthorizeAndDeleteTheWholeDeletedTree() {
        LocalDateTime marker = LocalDateTime.now();
        FileEntity folder = FileEntity.builder().id(1L).teamId(10L).fileName("docs").fileType("folder")
                .isDeleted(true).deletedAt(marker).build();
        FileEntity child = FileEntity.builder().id(2L).teamId(10L).parentId(1L).fileName("old.txt")
                .fileType("file").storagePath("teams/10/old.txt").isDeleted(false).deletedAt(marker).build();
        when(fileRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(folder));
        when(fileRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(child), List.of(), List.of(child), List.of());
        when(fileVersionRepository.findByFileIdOrderByVersionNumberDesc(anyLong())).thenReturn(List.of());

        fileService.permanentDeleteTeamFile(10L, 2L, 1L);

        verify(teamPermissionService).requireDelete(2L, 10L, 1L);
        verify(teamPermissionService).requireDelete(2L, 10L, 2L);
        verify(storageService).deleteObject("teams/10/old.txt");
        verify(fileRepository).deleteById(2L);
        verify(fileRepository).deleteById(1L);
        verify(teamAuditService).record(10L, 2L, "FILE_PERMANENT_DELETE", "FILE", 1L, "docs");
    }

    @Test
    void searchFiles_shouldReturnMatchingFiles() {
        FileEntity file = FileEntity.builder().id(1L).userId(1L).fileName("report.pdf").build();
        Page<FileEntity> page = new PageImpl<>(List.of(file), PageRequest.of(0, 20), 1);
        when(fileRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        PageResult<FileEntity> result = fileService.searchFiles(1L, "report", 1, 20);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
    }

    @Test
    void adminGetFile_shouldReturnFile() {
        FileEntity file = FileEntity.builder().id(1L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        FileEntity result = fileService.adminGetFile(1L);

        assertThat(result).isSameAs(file);
    }

    @Test
    void adminGetFile_shouldThrowWhenDeleted() {
        FileEntity file = FileEntity.builder().id(1L).isDeleted(true).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> fileService.adminGetFile(1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void adminDeleteFile_shouldSoftDelete() {
        FileEntity file = FileEntity.builder().id(1L).isDeleted(false).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        ArgumentCaptor<FileEntity> captor = ArgumentCaptor.forClass(FileEntity.class);
        when(fileRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        fileService.adminDeleteFile(1L);

        assertThat(captor.getValue().getIsDeleted()).isTrue();
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
    }
}
