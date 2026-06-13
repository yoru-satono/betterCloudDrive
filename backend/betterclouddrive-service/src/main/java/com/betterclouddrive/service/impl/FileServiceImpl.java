package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.service.FileService;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileRepository fileRepository;
    private final StringRedisTemplate redisTemplate;

    private static final String STORAGE_INCR_PREFIX = "storage:incr:";

    @Override
    public PageResult<FileEntity> listFiles(Long userId, Long parentId, int page, int size, String sortBy, String order) {
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
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
        if (file == null || !file.getUserId().equals(userId) || file.getIsDeleted()) {
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
        file.setParentId(targetParentId);
        file.setUpdatedAt(LocalDateTime.now());
        fileRepository.save(file);
    }

    @Override
    @Transactional
    public void copyFile(Long userId, Long fileId, Long targetParentId) {
        FileEntity source = getFile(userId, fileId);
        FileEntity copy = FileEntity.builder()
                .userId(userId)
                .parentId(targetParentId)
                .fileName(source.getFileName() + " (copy)")
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
    public PageResult<FileEntity> listRecycleBin(Long userId, int page, int size) {
        Specification<FileEntity> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("userId"), userId),
                cb.equal(root.get("isDeleted"), true)
        );
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "deletedAt"));
        Page<FileEntity> result = fileRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public void permanentDelete(Long userId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !file.getUserId().equals(userId) || !file.getIsDeleted()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        fileRepository.deleteById(fileId);
    }

    @Override
    @Transactional
    public void emptyRecycleBin(Long userId) {
        fileRepository.deleteByUserIdAndIsDeletedTrue(userId);
    }

    @Override
    public PageResult<FileEntity> searchFiles(Long userId, String keyword, int page, int size) {
        Specification<FileEntity> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("userId"), userId),
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
}
