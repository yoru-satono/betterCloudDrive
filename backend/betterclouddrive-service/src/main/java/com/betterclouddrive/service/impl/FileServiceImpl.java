package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FileVersionRepository;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.storage.StorageService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileRepository fileRepository;
    private final StringRedisTemplate redisTemplate;
    private final StorageService storageService;
    private final FileVersionRepository fileVersionRepository;
    private final TeamAuditService teamAuditService;

    private static final String STORAGE_INCR_PREFIX = "storage:incr:";
    private static final String TEAM_STORAGE_INCR_PREFIX = "team:storage:incr:";

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

        PageRequest pageable = PageRequest.of(page - 1, size,
                Sort.by("fileType").ascending().and(Sort.by("fileName").ascending()));
        Page<FileEntity> result = fileRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
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
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            predicates.add(cb.isNull(root.get("teamId")));
            if (parentId != null) {
                predicates.add(cb.equal(root.get("parentId"), parentId));
            } else {
                predicates.add(cb.isNull(root.get("parentId")));
            }
            predicates.add(cb.equal(root.get("fileName"), folderName));
            predicates.add(cb.equal(root.get("fileType"), "folder"));
            predicates.add(cb.equal(root.get("isDeleted"), false));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        if (fileRepository.count(spec) > 0) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }

        FileEntity folder = FileEntity.builder()
                .userId(userId)
                .teamId(null)
                .parentId(parentId)
                .fileName(folderName)
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
        FileEntity file = getFile(userId, fileId);
        file.setFileName(newName);
        file.setUpdatedAt(LocalDateTime.now());
        return fileRepository.save(file);
    }

    @Override
    @Transactional
    public void moveFile(Long userId, Long fileId, Long targetParentId) {
        FileEntity file = getFile(userId, fileId);
        FileEntity targetParent = validateTargetParent(userId, targetParentId);
        if ("folder".equals(file.getFileType()) && targetParent != null && isSameOrDescendant(targetParent, file)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Cannot move a folder into itself or its subfolder");
        }
        if (!Objects.equals(file.getParentId(), targetParentId) && nameExists(userId, targetParentId, file.getFileName())) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }
        file.setParentId(targetParentId);
        file.setUpdatedAt(LocalDateTime.now());
        fileRepository.save(file);
    }

    @Override
    @Transactional
    public void copyFile(Long userId, Long fileId, Long targetParentId) {
        FileEntity source = getFile(userId, fileId);
        FileEntity targetParent = validateTargetParent(userId, targetParentId);
        if ("folder".equals(source.getFileType()) && targetParent != null && isSameOrDescendant(targetParent, source)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Cannot copy a folder into itself or its subfolder");
        }
        String copyName = source.getFileName() + " (copy)";
        if (nameExists(userId, targetParentId, copyName)) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }
        FileEntity copy = FileEntity.builder()
                .userId(userId)
                .teamId(null)
                .parentId(targetParentId)
                .fileName(copyName)
                .fileType(source.getFileType())
                .mimeType(source.getMimeType())
                .fileSize(source.getFileSize())
                .storagePath(source.getStoragePath())
                .md5Hash(source.getMd5Hash())
                .isDeleted(false)
                .versionCount(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        fileRepository.save(copy);
    }

    @Override
    @Transactional
    public void deleteFiles(Long userId, List<Long> fileIds) {
        for (Long fileId : fileIds) {
            FileEntity file = getFile(userId, fileId);
            file.setIsDeleted(true);
            file.setDeletedAt(LocalDateTime.now());
            fileRepository.save(file);
            if ("file".equals(file.getFileType()) && file.getFileSize() > 0) {
                redisTemplate.opsForValue().increment(STORAGE_INCR_PREFIX + userId, -file.getFileSize());
            }
        }
    }

    @Override
    @Transactional
    public void restoreFile(Long userId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !file.getUserId().equals(userId) || !file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        file.setIsDeleted(false);
        file.setDeletedAt(null);
        fileRepository.save(file);
    }

    @Override
    public PageResult<FileEntity> listRecycleBin(Long userId, String keyword, int page, int size) {
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            predicates.add(cb.isNull(root.get("teamId")));
            predicates.add(cb.equal(root.get("isDeleted"), true));
            if (StringUtils.hasText(keyword)) {
                predicates.add(cb.like(cb.lower(root.get("fileName")), "%" + keyword.trim().toLowerCase() + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "deletedAt"));
        Page<FileEntity> result = fileRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public void permanentDelete(Long userId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || file.getTeamId() != null || !file.getUserId().equals(userId) || !file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        deleteTree(file);
    }

    @Override
    @Transactional
    public void emptyRecycleBin(Long userId) {
        listDeletedFiles(null, userId).forEach(this::deleteTree);
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
        FileEntity file = adminGetFile(fileId);
        file.setIsDeleted(true);
        file.setDeletedAt(LocalDateTime.now());
        fileRepository.save(file);
    }

    @Override
    @Transactional
    public FileEntity copyFileTo(Long userId, Long fileId, Long targetParentId, String targetFileName) {
        FileEntity source = getFile(userId, fileId);
        FileEntity copy = FileEntity.builder()
                .userId(userId)
                .teamId(null)
                .parentId(targetParentId)
                .fileName(targetFileName)
                .fileType(source.getFileType())
                .mimeType(source.getMimeType())
                .fileSize(source.getFileSize())
                .storagePath(source.getStoragePath())
                .md5Hash(source.getMd5Hash())
                .isDeleted(false)
                .versionCount(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        return fileRepository.save(copy);
    }

    @Override
    public PageResult<FileEntity> listTeamFiles(Long teamId, Long parentId, int page, int size, String sortBy, String order) {
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

        PageRequest pageable = PageRequest.of(page - 1, size,
                Sort.by("fileType").ascending().and(Sort.by("fileName").ascending()));
        Page<FileEntity> result = fileRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    public FileEntity getTeamFile(Long teamId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !Objects.equals(file.getTeamId(), teamId) || file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    @Override
    @Transactional
    public FileEntity createTeamFolder(Long teamId, Long userId, Long parentId, String folderName) {
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
        FileEntity file = getTeamFile(teamId, fileId);
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
        FileEntity file = getTeamFile(teamId, fileId);
        FileEntity targetParent = validateTeamTargetParent(teamId, targetParentId);
        if ("folder".equals(file.getFileType()) && targetParent != null && isSameOrDescendant(targetParent, file)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Cannot move a folder into itself or its subfolder");
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
        for (Long fileId : fileIds) {
            FileEntity file = getTeamFile(teamId, fileId);
            file.setIsDeleted(true);
            file.setDeletedAt(LocalDateTime.now());
            fileRepository.save(file);
            teamAuditService.record(teamId, actorUserId, "FILE_DELETE", "FILE", fileId, file.getFileName());
            if ("file".equals(file.getFileType()) && file.getFileSize() > 0) {
                redisTemplate.opsForValue().increment(TEAM_STORAGE_INCR_PREFIX + teamId, -file.getFileSize());
            }
        }
    }

    @Override
    @Transactional
    public void restoreTeamFile(Long teamId, Long actorUserId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !Objects.equals(file.getTeamId(), teamId) || !file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        if (teamNameExists(teamId, file.getParentId(), file.getFileName())) {
            throw new BusinessException(ApiCode.FILE_NAME_CONFLICT);
        }
        file.setIsDeleted(false);
        file.setDeletedAt(null);
        fileRepository.save(file);
        teamAuditService.record(teamId, actorUserId, "FILE_RESTORE", "FILE", fileId, file.getFileName());
    }

    @Override
    public PageResult<FileEntity> listTeamRecycleBin(Long teamId, String keyword, int page, int size) {
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("teamId"), teamId));
            predicates.add(cb.equal(root.get("isDeleted"), true));
            if (StringUtils.hasText(keyword)) {
                predicates.add(cb.like(cb.lower(root.get("fileName")), "%" + keyword.trim().toLowerCase() + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "deletedAt"));
        Page<FileEntity> result = fileRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public void permanentDeleteTeamFile(Long teamId, Long actorUserId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !Objects.equals(file.getTeamId(), teamId) || !file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        teamAuditService.record(teamId, actorUserId, "FILE_PERMANENT_DELETE", "FILE", fileId, file.getFileName());
        deleteTree(file);
    }

    @Override
    @Transactional
    public void emptyTeamRecycleBin(Long teamId, Long actorUserId) {
        teamAuditService.record(teamId, actorUserId, "RECYCLE_EMPTY", "TEAM", teamId, null);
        listDeletedFiles(teamId, null).forEach(this::deleteTree);
    }

    @Override
    public PageResult<FileEntity> searchTeamFiles(Long teamId, String keyword, int page, int size) {
        Specification<FileEntity> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("teamId"), teamId),
                cb.equal(root.get("isDeleted"), false),
                cb.like(cb.lower(root.get("fileName")), "%" + keyword.toLowerCase() + "%")
        );
        PageRequest pageable = PageRequest.of(page - 1, size,
                Sort.by("fileType").ascending().and(Sort.by("fileName").ascending()));
        Page<FileEntity> result = fileRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
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

    private FileEntity validateTeamTargetParent(Long teamId, Long targetParentId) {
        if (targetParentId == null) {
            return null;
        }
        FileEntity targetParent = fileRepository.findById(targetParentId).orElse(null);
        if (targetParent == null
                || targetParent.getIsDeleted()
                || !Objects.equals(targetParent.getTeamId(), teamId)
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
}
