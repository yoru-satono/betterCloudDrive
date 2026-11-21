package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FileVersionRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.storage.StorageService;
import jakarta.persistence.criteria.Predicate;
import com.betterclouddrive.service.quota.QuotaAccountingService;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileRepository fileRepository;
    private final QuotaAccountingService quotaAccounting;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final FileVersionRepository fileVersionRepository;
    private final TeamAuditService teamAuditService;
    private final TeamPermissionService teamPermissionService;
    private final TeamRepository teamRepository;

    private static final String DEFAULT_FILE_SORT = "fileName";
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final String COPY_SUFFIX = " (copy)";
    private static final String INVALID_FILE_NAME = "error.invalidFileName";
    private static final String INVALID_FILE_DESTINATION = "error.invalidFileDestination";

    @Override
    public PageResult<FileEntity> listFiles(Long userId, Long parentId, int page, int size, String sortBy, String order) {
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            predicates.add(cb.isNull(root.get("teamId")));
            if (parentId != null) {
                predicates.add(cb.equal(root.get("parentId"), parentId));
            } else {
                predicates.add(cb.isNull(root.get("parentId")));
            }
            predicates.add(cb.equal(root.get("isDeleted"), false));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageable = PageRequest.of(page - 1, size, personalFileSort(sortBy, order));
        Page<FileEntity> result = fileRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    private Sort personalFileSort(String requestedSortBy, String requestedOrder) {
        String safeSortBy = switch (requestedSortBy == null ? "" : requestedSortBy) {
            case "updatedAt", "fileSize" -> requestedSortBy;
            default -> DEFAULT_FILE_SORT;
        };
        Sort.Direction direction = "desc".equalsIgnoreCase(requestedOrder)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        Sort.Order selected = new Sort.Order(direction, safeSortBy);
        if ("updatedAt".equals(safeSortBy)) {
            selected = selected.nullsLast();
        }

        Sort sort = Sort.by(Sort.Order.desc("fileType"), selected);
        if (!DEFAULT_FILE_SORT.equals(safeSortBy)) {
            sort = sort.and(Sort.by(Sort.Order.asc(DEFAULT_FILE_SORT)));
        }
        return sort.and(Sort.by(Sort.Order.asc("id")));
    }

    @Override
    public FileEntity getFile(Long userId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || file.getTeamId() != null || !file.getUserId().equals(userId) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    @Override
    @Transactional
    public FileEntity createFolder(Long userId, Long parentId, String folderName) {
        lockPersonalMutations(userId);
        String normalizedName = normalizeFileName(folderName);
        validateTargetParent(userId, parentId);
        if (nameExists(userId, parentId, normalizedName)) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }

        FileEntity folder = FileEntity.builder()
                .userId(userId)
                .teamId(null)
                .parentId(parentId)
                .fileName(normalizedName)
                .fileType("folder")
                .fileSize(0L)
                .isDeleted(false)
                .versionCount(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        return fileRepository.save(folder);
    }

    @Override
    @Transactional
    public FileEntity renameFile(Long userId, Long fileId, String newName) {
        lockPersonalMutations(userId);
        FileEntity file = getFile(userId, fileId);
        String normalizedName = normalizeFileName(newName);
        if (Objects.equals(file.getFileName(), normalizedName)) {
            return file;
        }
        if (nameExists(userId, file.getParentId(), normalizedName)) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }
        file.setFileName(normalizedName);
        file.setUpdatedAt(LocalDateTime.now());
        return fileRepository.save(file);
    }

    @Override
    @Transactional
    public void moveFile(Long userId, Long fileId, Long targetParentId) {
        lockPersonalMutations(userId);
        FileEntity file = getFile(userId, fileId);
        FileEntity targetParent = validateTargetParent(userId, targetParentId);
        if ("folder".equals(file.getFileType()) && targetParent != null && isSameOrDescendant(targetParent, file)) {
            throw invalidDestination("Cannot move a folder into itself or its descendant");
        }
        if (Objects.equals(file.getParentId(), targetParentId)) {
            return;
        }
        if (nameExists(userId, targetParentId, file.getFileName())) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }
        file.setParentId(targetParentId);
        file.setUpdatedAt(LocalDateTime.now());
        fileRepository.save(file);
    }

    @Override
    @Transactional
    public void copyFile(Long userId, Long fileId, Long targetParentId) {
        lockPersonalMutations(userId);
        FileEntity source = getFile(userId, fileId);
        copyPersonalTree(userId, source, targetParentId, copyName(source.getFileName()));
    }

    @Override
    @Transactional
    public void deleteFiles(Long userId, List<Long> fileIds) {
        lockPersonalMutations(userId);
        List<FileEntity> requested = new LinkedHashSet<>(fileIds).stream()
                .map(fileId -> getFile(userId, fileId))
                .toList();
        java.util.Set<Long> requestedIds = requested.stream()
                .map(FileEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<FileEntity> roots = requested.stream()
                .filter(file -> !hasSelectedAncestor(file, requestedIds))
                .toList();
        LocalDateTime deletedAt = LocalDateTime.now();
        long releasedBytes = 0;
        for (FileEntity root : roots) {
            releasedBytes = Math.addExact(releasedBytes, activeTreeStoredBytes(root));
            softDeleteTree(root, deletedAt);
        }
        if (releasedBytes > 0) {
            quotaAccounting.change(Scope.PERSONAL, userId, -releasedBytes);
        }
    }

    @Override
    @Transactional
    public void restoreFile(Long userId, Long fileId) {
        lockPersonalMutations(userId);
        FileEntity file = fileRepository.findByIdForUpdate(fileId).orElse(null);
        if (file == null || file.getTeamId() != null || !file.getUserId().equals(userId) || !isRecycleRoot(file)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        Long targetParentId = validRestoreParent(userId, file.getParentId()) ? file.getParentId() : null;
        if (nameExists(userId, targetParentId, file.getFileName())) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }
        long restoredBytes = deletedTreeStoredBytes(file, file.getDeletedAt());
        if (restoredBytes > 0) {
            checkStorageQuota(userId, restoredBytes);
        }
        file.setParentId(targetParentId);
        restoreDeletedTree(file, file.getDeletedAt());
        if (restoredBytes > 0) {
            quotaAccounting.change(Scope.PERSONAL, userId, restoredBytes);
        }
    }

    @Override
    public PageResult<FileEntity> listRecycleBin(Long userId, String keyword, int page, int size) {
        String normalized = StringUtils.hasText(keyword) ? keyword.trim() : null;
        PageRequest pageable = PageRequest.of(page - 1, size);
        Page<FileEntity> result = fileRepository.findPersonalRecycleRoots(userId, normalized, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public void permanentDelete(Long userId, Long fileId) {
        lockPersonalMutations(userId);
        FileEntity file = fileRepository.findByIdForUpdate(fileId).orElse(null);
        if (file == null || file.getTeamId() != null || !file.getUserId().equals(userId) || !isRecycleRoot(file)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        deleteTree(file);
    }

    @Override
    @Transactional
    public void emptyRecycleBin(Long userId) {
        lockPersonalMutations(userId);
        listDeletedFiles(null, userId).stream().filter(this::isRecycleRoot).forEach(this::deleteTree);
    }

    @Override
    @Transactional
    public void purgeExpiredRecycleRoot(Long fileId) {
        quotaAccounting.lockForFile(fileId);
        FileEntity file = fileRepository.findByIdForUpdate(fileId).orElse(null);
        if (file == null || !file.getIsDeleted()) {
            return;
        }
        deleteTree(file);
    }

    @Override
    public PageResult<FileEntity> searchFiles(Long userId, String keyword, int page, int size) {
        Specification<FileEntity> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("userId"), userId),
                cb.isNull(root.get("teamId")),
                cb.equal(root.get("isDeleted"), false),
                cb.like(cb.lower(root.get("fileName")), "%" + keyword.toLowerCase() + "%")
        );
        PageRequest pageable = PageRequest.of(page - 1, size,
                Sort.by("fileType").ascending().and(Sort.by("fileName").ascending()));
        Page<FileEntity> result = fileRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    public FileEntity adminGetFile(Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    @Override
    @Transactional
    public void adminDeleteFile(Long fileId) {
        quotaAccounting.lockForFile(fileId);
        FileEntity file = adminGetFile(fileId);
        long released = activeTreeStoredBytes(file);
        softDeleteTree(file, LocalDateTime.now());
        if (released > 0) quotaAccounting.change(file.getTeamId() == null ? Scope.PERSONAL : Scope.TEAM,
                file.getTeamId() == null ? file.getUserId() : file.getTeamId(), -released);
    }

    @Override
    @Transactional
    public FileEntity copyFileTo(Long userId, Long fileId, Long targetParentId, String targetFileName) {
        lockPersonalMutations(userId);
        FileEntity source = getFile(userId, fileId);
        return copyPersonalTree(userId, source, targetParentId, normalizeFileName(targetFileName));
    }

    @Override
    public PageResult<FileEntity> listTeamFiles(Long userId, Long teamId, Long parentId, int page, int size, String sortBy, String order) {
        if (parentId != null) {
            FileEntity parent = getTeamFile(teamId, parentId);
            if (!"folder".equals(parent.getFileType())) throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("teamId"), teamId));
            if (parentId != null) {
                predicates.add(cb.equal(root.get("parentId"), parentId));
            } else {
                predicates.add(cb.isNull(root.get("parentId")));
            }
            predicates.add(cb.equal(root.get("isDeleted"), false));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        List<FileEntity> readable = fileRepository
                .findAll(spec, personalFileSort(sortBy, order))
                .stream()
                .filter(this::hasActiveAncestors)
                .filter(file -> canRead(userId, teamId, file.getId()))
                .toList();
        return PageResults.fromFiltered(readable, page, size);
    }

    @Override
    public FileEntity getTeamFile(Long teamId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !Objects.equals(file.getTeamId(), teamId) || file.getIsDeleted() || !hasActiveAncestors(file)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    @Override
    @Transactional
    public FileEntity createTeamFolder(Long teamId, Long userId, Long parentId, String folderName) {
        lockTeamMutations(teamId);
        teamPermissionService.requireParentWrite(userId, teamId, parentId);
        folderName = normalizeFileName(folderName);
        validateTeamTargetParent(teamId, parentId);
        if (teamNameExists(teamId, parentId, folderName)) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }
        FileEntity folder = FileEntity.builder()
                .userId(userId)
                .teamId(teamId)
                .parentId(parentId)
                .fileName(folderName)
                .fileType("folder")
                .fileSize(0L)
                .isDeleted(false)
                .versionCount(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        FileEntity saved = fileRepository.save(folder);
        teamAuditService.record(teamId, userId, "FILE_CREATE_FOLDER", "FILE", saved.getId(), saved.getFileName());
        return saved;
    }

    @Override
    @Transactional
    public FileEntity renameTeamFile(Long teamId, Long actorUserId, Long fileId, String newName) {
        lockTeamMutations(teamId);
        teamPermissionService.requireWrite(actorUserId, teamId, fileId);
        newName = normalizeFileName(newName);
        FileEntity file = getTeamFile(teamId, fileId);
        if (Objects.equals(file.getFileName(), newName)) {
            return file;
        }
        if (!Objects.equals(file.getFileName(), newName) && teamNameExists(teamId, file.getParentId(), newName)) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }
        file.setFileName(newName);
        file.setUpdatedAt(LocalDateTime.now());
        FileEntity saved = fileRepository.save(file);
        teamAuditService.record(teamId, actorUserId, "FILE_RENAME", "FILE", fileId, newName);
        return saved;
    }

    @Override
    @Transactional
    public void moveTeamFile(Long teamId, Long actorUserId, Long fileId, Long targetParentId) {
        lockTeamMutations(teamId);
        teamPermissionService.requireWrite(actorUserId, teamId, fileId);
        teamPermissionService.requireParentWrite(actorUserId, teamId, targetParentId);
        FileEntity file = getTeamFile(teamId, fileId);
        if (Objects.equals(file.getParentId(), targetParentId)) {
            return;
        }
        FileEntity targetParent = validateTeamTargetParent(teamId, targetParentId);
        if ("folder".equals(file.getFileType()) && targetParent != null && isSameOrDescendant(targetParent, file)) {
            throw invalidDestination("Cannot move a folder into itself or its descendant");
        }
        if (!Objects.equals(file.getParentId(), targetParentId) && teamNameExists(teamId, targetParentId, file.getFileName())) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }
        file.setParentId(targetParentId);
        file.setUpdatedAt(LocalDateTime.now());
        fileRepository.save(file);
        teamAuditService.record(teamId, actorUserId, "FILE_MOVE", "FILE", fileId, String.valueOf(targetParentId));
    }

    @Override
    @Transactional
    public void deleteTeamFiles(Long teamId, Long actorUserId, List<Long> fileIds) {
        lockTeamMutations(teamId);
        List<FileEntity> requested = new LinkedHashSet<>(fileIds).stream()
                .map(fileId -> getTeamFile(teamId, fileId))
                .toList();
        java.util.Set<Long> requestedIds = requested.stream().map(FileEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<FileEntity> roots = requested.stream()
                .filter(file -> !hasSelectedTeamAncestor(file, requestedIds))
                .toList();
        for (FileEntity root : roots) {
            requireDeleteTree(actorUserId, teamId, root);
        }
        LocalDateTime deletedAt = LocalDateTime.now();
        long releasedBytes = 0;
        for (FileEntity root : roots) {
            releasedBytes = Math.addExact(releasedBytes, activeTreeStoredBytes(root));
            softDeleteTree(root, deletedAt);
            teamAuditService.record(teamId, actorUserId, "FILE_DELETE", "FILE", root.getId(), root.getFileName());
        }
        if (releasedBytes > 0) {
            quotaAccounting.change(Scope.TEAM, teamId, -releasedBytes);
        }
    }

    @Override
    @Transactional
    public void restoreTeamFile(Long teamId, Long actorUserId, Long fileId) {
        lockTeamMutations(teamId);
        FileEntity file = fileRepository.findByIdForUpdate(fileId).orElse(null);
        if (file == null || !Objects.equals(file.getTeamId(), teamId) || !isRecycleRoot(file)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        teamPermissionService.requireWrite(actorUserId, teamId, fileId);
        Long targetParentId = validTeamRestoreParent(teamId, file.getParentId()) ? file.getParentId() : null;
        teamPermissionService.requireParentWrite(actorUserId, teamId, targetParentId);
        if (teamNameExists(teamId, targetParentId, file.getFileName())) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }
        long restoredBytes = deletedTreeStoredBytes(file, file.getDeletedAt());
        if (restoredBytes > 0) {
            checkTeamStorageQuota(teamId, restoredBytes);
        }
        file.setParentId(targetParentId);
        restoreDeletedTree(file, file.getDeletedAt());
        if (restoredBytes > 0) {
            quotaAccounting.change(Scope.TEAM, teamId, restoredBytes);
        }
        teamAuditService.record(teamId, actorUserId, "FILE_RESTORE", "FILE", fileId, file.getFileName());
    }

    @Override
    public PageResult<FileEntity> listTeamRecycleBin(Long userId, Long teamId, String keyword, int page, int size) {
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("teamId"), teamId));
            predicates.add(cb.equal(root.get("isDeleted"), true));
            if (StringUtils.hasText(keyword)) {
                predicates.add(cb.like(cb.lower(root.get("fileName")), "%" + keyword.trim().toLowerCase() + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        List<FileEntity> readable = fileRepository
                .findAll(spec, Sort.by(
                        new Sort.Order(Sort.Direction.DESC, "deletedAt").nullsLast(),
                        new Sort.Order(Sort.Direction.DESC, "id")))
                .stream()
                .filter(this::isRecycleRoot)
                .filter(file -> canRead(userId, teamId, file.getId()))
                .toList();
        return PageResults.fromFiltered(readable, page, size);
    }

    @Override
    @Transactional
    public void permanentDeleteTeamFile(Long teamId, Long actorUserId, Long fileId) {
        lockTeamMutations(teamId);
        FileEntity file = fileRepository.findByIdForUpdate(fileId).orElse(null);
        if (file == null || !Objects.equals(file.getTeamId(), teamId) || !isRecycleRoot(file)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        requireDeleteStoredTree(actorUserId, teamId, file);
        teamAuditService.record(teamId, actorUserId, "FILE_PERMANENT_DELETE", "FILE", fileId, file.getFileName());
        deleteTree(file);
    }

    @Override
    @Transactional
    public void emptyTeamRecycleBin(Long teamId, Long actorUserId) {
        lockTeamMutations(teamId);
        teamAuditService.record(teamId, actorUserId, "RECYCLE_EMPTY", "TEAM", teamId, null);
        listDeletedFiles(teamId, null).stream().filter(this::isRecycleRoot).forEach(this::deleteTree);
    }

    @Override
    public PageResult<FileEntity> searchTeamFiles(Long userId, Long teamId, String keyword, int page, int size) {
        String normalized = StringUtils.hasText(keyword) ? keyword.trim() : "";
        if (normalized.isEmpty()) {
            return PageResult.of(List.of(), 0, page, size);
        }
        Specification<FileEntity> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("teamId"), teamId),
                cb.equal(root.get("isDeleted"), false),
                cb.like(cb.lower(root.get("fileName")), "%" + normalized.toLowerCase() + "%")
        );
        List<FileEntity> readable = fileRepository
                .findAll(spec, Sort.by(
                        Sort.Order.desc("fileType"),
                        Sort.Order.asc("fileName"),
                        Sort.Order.asc("id")))
                .stream()
                .filter(this::hasActiveAncestors)
                .filter(file -> canRead(userId, teamId, file.getId()))
                .toList();
        return PageResults.fromFiltered(readable, page, size);
    }

    private boolean canRead(Long userId, Long teamId, Long fileId) {
        return teamPermissionService.effectivePermissions(userId, teamId, fileId).canRead();
    }

    private FileEntity copyPersonalTree(Long userId, FileEntity source, Long targetParentId, String targetFileName) {
        targetFileName = normalizeFileName(targetFileName);
        FileEntity targetParent = validateTargetParent(userId, targetParentId);
        if ("folder".equals(source.getFileType()) && targetParent != null && isSameOrDescendant(targetParent, source)) {
            throw invalidDestination("Cannot copy a folder into itself or its descendant");
        }
        if (nameExists(userId, targetParentId, targetFileName)) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }

        long totalSize = calculateTreeFileSize(source);
        if (totalSize > 0) {
            checkStorageQuota(userId, totalSize);
        }

        FileEntity copied = copyTree(source, userId, targetParentId, targetFileName);
        if (totalSize > 0) {
            quotaAccounting.change(Scope.PERSONAL, userId, totalSize);
        }
        return copied;
    }

    private FileEntity copyTree(FileEntity source, Long targetUserId, Long targetParentId, String targetFileName) {
        FileEntity copy = FileEntity.builder()
                .userId(targetUserId)
                .teamId(null)
                .parentId(targetParentId)
                .fileName(targetFileName)
                .fileType(source.getFileType())
                .mimeType(source.getMimeType())
                .fileSize(safeFileSize(source))
                .storagePath(source.getStoragePath())
                .md5Hash(source.getMd5Hash())
                .isDeleted(false)
                .versionCount(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        FileEntity saved = fileRepository.save(copy);

        if ("folder".equals(source.getFileType())) {
            for (FileEntity child : activeChildrenOf(source)) {
                copyTree(child, targetUserId, saved.getId(), child.getFileName());
            }
        }
        return saved;
    }

    private long calculateTreeFileSize(FileEntity source) {
        if ("file".equals(source.getFileType())) {
            return safeFileSize(source);
        }
        long total = 0L;
        for (FileEntity child : activeChildrenOf(source)) {
            total += calculateTreeFileSize(child);
        }
        return total;
    }

    private long safeFileSize(FileEntity file) {
        return file.getFileSize() != null ? file.getFileSize() : 0L;
    }

    private void checkStorageQuota(Long userId, long additionalBytes) {
        quotaAccounting.check(Scope.PERSONAL, userId, additionalBytes);
    }

    private void checkTeamStorageQuota(Long teamId, long additionalBytes) {
        quotaAccounting.check(Scope.TEAM, teamId, additionalBytes);
    }

    private FileEntity validateTargetParent(Long userId, Long targetParentId) {
        if (targetParentId == null) {
            return null;
        }
        FileEntity targetParent = fileRepository.findById(targetParentId).orElse(null);
        if (targetParent == null
                || targetParent.getIsDeleted()
                || targetParent.getTeamId() != null
                || !targetParent.getUserId().equals(userId)
                || !"folder".equals(targetParent.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return targetParent;
    }

    private boolean isSameOrDescendant(FileEntity targetParent, FileEntity sourceFolder) {
        FileEntity current = targetParent;
        while (current != null) {
            if (current.getId().equals(sourceFolder.getId())) {
                return true;
            }
            Long parentId = current.getParentId();
            if (parentId == null) {
                return false;
            }
            current = fileRepository.findById(parentId).orElse(null);
            if (current == null || current.getIsDeleted() || !sameFileSpace(current, sourceFolder)) {
                return false;
            }
        }
        return false;
    }

    private boolean sameFileSpace(FileEntity left, FileEntity right) {
        if (left.getTeamId() != null || right.getTeamId() != null) {
            return Objects.equals(left.getTeamId(), right.getTeamId());
        }
        return Objects.equals(left.getUserId(), right.getUserId());
    }

    private boolean nameExists(Long userId, Long targetParentId, String fileName) {
        if (targetParentId == null) {
            return fileRepository.existsByUserIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(userId, fileName);
        }
        return fileRepository.existsByUserIdAndParentIdAndFileNameAndIsDeletedFalse(userId, targetParentId, fileName);
    }

    private void lockPersonalMutations(Long userId) {
        quotaAccounting.lock(Scope.PERSONAL, userId);
        if (userRepository.findByIdForUpdate(userId).isEmpty()) {
            throw new BusinessException(ApiCode.UNAUTHORIZED);
        }
    }

    private String normalizeFileName(String fileName) {
        String normalized = fileName == null ? "" : fileName.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_FILE_NAME_LENGTH) {
            throw new BusinessException(ApiCode.BAD_REQUEST, INVALID_FILE_NAME, java.util.Map.of());
        }
        return normalized;
    }

    private String copyName(String sourceName) {
        int maxSourceLength = MAX_FILE_NAME_LENGTH - COPY_SUFFIX.length();
        String safeSource = sourceName == null ? "" : sourceName;
        if (safeSource.length() > maxSourceLength) {
            safeSource = safeSource.substring(0, maxSourceLength);
            if (!safeSource.isEmpty() && Character.isHighSurrogate(safeSource.charAt(safeSource.length() - 1))) {
                safeSource = safeSource.substring(0, safeSource.length() - 1);
            }
        }
        return safeSource + COPY_SUFFIX;
    }

    private BusinessException invalidDestination(String diagnostic) {
        return new BusinessException(ApiCode.BAD_REQUEST.getCode(), INVALID_FILE_DESTINATION,
                java.util.Map.of(), diagnostic);
    }

    private FileEntity validateTeamTargetParent(Long teamId, Long targetParentId) {
        if (targetParentId == null) {
            return null;
        }
        FileEntity targetParent = fileRepository.findById(targetParentId).orElse(null);
        if (targetParent == null
                || targetParent.getIsDeleted()
                || !Objects.equals(targetParent.getTeamId(), teamId)
                || !hasActiveAncestors(targetParent)
                || !"folder".equals(targetParent.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return targetParent;
    }

    private boolean teamNameExists(Long teamId, Long targetParentId, String fileName) {
        if (targetParentId == null) {
            return fileRepository.existsByTeamIdAndParentIdIsNullAndFileNameAndIsDeletedFalse(teamId, fileName);
        }
        return fileRepository.existsByTeamIdAndParentIdAndFileNameAndIsDeletedFalse(teamId, targetParentId, fileName);
    }

    private List<FileEntity> listDeletedFiles(Long teamId, Long userId) {
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (teamId != null) {
                predicates.add(cb.equal(root.get("teamId"), teamId));
            } else {
                predicates.add(cb.equal(root.get("userId"), userId));
                predicates.add(cb.isNull(root.get("teamId")));
            }
            predicates.add(cb.equal(root.get("isDeleted"), true));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return fileRepository.findAll(spec);
    }

    private void deleteTree(FileEntity file) {
        for (FileEntity child : childrenOf(file)) {
            deleteTree(child);
        }
        if ("file".equals(file.getFileType()) && file.getStoragePath() != null && !file.getStoragePath().isBlank()) {
            storageService.deleteObject(file.getStoragePath());
        }
        if ("file".equals(file.getFileType()) && file.getThumbnailPath() != null && !file.getThumbnailPath().isBlank()) {
            storageService.deleteObject(file.getThumbnailPath());
        }
        fileVersionRepository.findByFileIdOrderByVersionNumberDesc(file.getId())
                .forEach(version -> {
                    if (version.getStoragePath() != null && !version.getStoragePath().isBlank()) {
                        storageService.deleteObject(version.getStoragePath());
                    }
                });
        fileVersionRepository.deleteByFileId(file.getId());
        fileRepository.deleteById(file.getId());
    }

    private List<FileEntity> childrenOf(FileEntity parent) {
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("parentId"), parent.getId()));
            if (parent.getTeamId() != null) {
                predicates.add(cb.equal(root.get("teamId"), parent.getTeamId()));
            } else {
                predicates.add(cb.equal(root.get("userId"), parent.getUserId()));
                predicates.add(cb.isNull(root.get("teamId")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return fileRepository.findAll(spec);
    }

    private List<FileEntity> activeChildrenOf(FileEntity parent) {
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("parentId"), parent.getId()));
            predicates.add(cb.equal(root.get("isDeleted"), false));
            if (parent.getTeamId() != null) {
                predicates.add(cb.equal(root.get("teamId"), parent.getTeamId()));
            } else {
                predicates.add(cb.equal(root.get("userId"), parent.getUserId()));
                predicates.add(cb.isNull(root.get("teamId")));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return fileRepository.findAll(spec);
    }

    private void lockTeamMutations(Long teamId) {
        quotaAccounting.lock(Scope.TEAM, teamId);
        if (teamRepository.findActiveByIdForUpdate(teamId).isEmpty()) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Team not found");
        }
    }

    private boolean hasSelectedAncestor(FileEntity file, java.util.Set<Long> selectedIds) {
        Long parentId = file.getParentId();
        while (parentId != null) {
            if (selectedIds.contains(parentId)) {
                return true;
            }
            FileEntity parent = fileRepository.findById(parentId).orElse(null);
            if (parent == null || parent.getTeamId() != null || !Objects.equals(parent.getUserId(), file.getUserId())) {
                return false;
            }
            parentId = parent.getParentId();
        }
        return false;
    }

    private boolean hasSelectedTeamAncestor(FileEntity file, java.util.Set<Long> selectedIds) {
        Long parentId = file.getParentId();
        while (parentId != null) {
            if (selectedIds.contains(parentId)) return true;
            FileEntity parent = fileRepository.findById(parentId).orElse(null);
            if (parent == null || !Objects.equals(parent.getTeamId(), file.getTeamId())) return false;
            parentId = parent.getParentId();
        }
        return false;
    }

    private void requireDeleteTree(Long userId, Long teamId, FileEntity file) {
        teamPermissionService.requireDelete(userId, teamId, file.getId());
        for (FileEntity child : activeChildrenOf(file)) {
            requireDeleteTree(userId, teamId, child);
        }
    }

    private void requireDeleteStoredTree(Long userId, Long teamId, FileEntity file) {
        teamPermissionService.requireDelete(userId, teamId, file.getId());
        for (FileEntity child : childrenOf(file)) {
            requireDeleteStoredTree(userId, teamId, child);
        }
    }

    private boolean hasActiveAncestors(FileEntity file) {
        Long parentId = file.getParentId();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        while (parentId != null && seen.add(parentId)) {
            FileEntity parent = fileRepository.findById(parentId).orElse(null);
            if (parent == null || parent.getIsDeleted() || !sameFileSpace(parent, file)) return false;
            parentId = parent.getParentId();
        }
        return parentId == null;
    }

    private long activeTreeStoredBytes(FileEntity file) {
        if (file.getIsDeleted()) {
            return 0;
        }
        long total = storedBytes(file);
        for (FileEntity child : activeChildrenOf(file)) {
            total = Math.addExact(total, activeTreeStoredBytes(child));
        }
        return total;
    }

    private long deletedTreeStoredBytes(FileEntity file, LocalDateTime deletionMarker) {
        long total = file.getIsDeleted() && Objects.equals(file.getDeletedAt(), deletionMarker) ? storedBytes(file) : 0;
        for (FileEntity child : childrenOf(file)) {
            total = Math.addExact(total, deletedTreeStoredBytes(child, deletionMarker));
        }
        return total;
    }

    private long storedBytes(FileEntity file) {
        if (!"file".equals(file.getFileType())) {
            return 0;
        }
        long total = Math.max(0L, file.getFileSize() == null ? 0L : file.getFileSize());
        for (FileVersionEntity version : fileVersionRepository.findByFileIdOrderByVersionNumberDesc(file.getId())) {
            total = Math.addExact(total, Math.max(0L, version.getFileSize() == null ? 0L : version.getFileSize()));
        }
        return total;
    }

    private void softDeleteTree(FileEntity file, LocalDateTime deletedAt) {
        if (file.getIsDeleted()) {
            return;
        }
        file.setIsDeleted(true);
        file.setDeletedAt(deletedAt);
        fileRepository.save(file);
        for (FileEntity child : activeChildrenOf(file)) {
            softDeleteTree(child, deletedAt);
        }
    }

    private void restoreDeletedTree(FileEntity file, LocalDateTime deletionMarker) {
        if (file.getIsDeleted() && Objects.equals(file.getDeletedAt(), deletionMarker)) {
            file.setIsDeleted(false);
            file.setDeletedAt(null);
            fileRepository.save(file);
        }
        for (FileEntity child : childrenOf(file)) {
            restoreDeletedTree(child, deletionMarker);
        }
    }

    private boolean validRestoreParent(Long userId, Long parentId) {
        if (parentId == null) {
            return true;
        }
        FileEntity parent = fileRepository.findById(parentId).orElse(null);
        return parent != null
                && parent.getTeamId() == null
                && Objects.equals(parent.getUserId(), userId)
                && !parent.getIsDeleted()
                && "folder".equals(parent.getFileType());
    }

    private boolean validTeamRestoreParent(Long teamId, Long parentId) {
        if (parentId == null) {
            return true;
        }
        FileEntity parent = fileRepository.findById(parentId).orElse(null);
        return parent != null
                && Objects.equals(parent.getTeamId(), teamId)
                && !parent.getIsDeleted()
                && hasActiveAncestors(parent)
                && "folder".equals(parent.getFileType());
    }

    private boolean isRecycleRoot(FileEntity file) {
        if (!file.getIsDeleted()) {
            return false;
        }
        if (file.getParentId() == null) {
            return true;
        }
        FileEntity parent = fileRepository.findById(file.getParentId()).orElse(null);
        return parent == null || !parent.getIsDeleted();
    }
}
