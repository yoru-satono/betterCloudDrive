package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FolderZipDownloadCacheEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.FolderZipDownloadCacheRepository;
import com.betterclouddrive.service.FolderZipDownloadService;
import com.betterclouddrive.service.config.FolderZipDownloadProperties;
import com.betterclouddrive.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class FolderZipDownloadServiceImpl implements FolderZipDownloadService {

    private final FileRepository fileRepository;
    private final FolderZipDownloadCacheRepository cacheRepository;
    private final StorageService storageService;
    private final FolderZipDownloadProperties properties;

    @Override
    public void validateDownloadable(FileEntity folder) {
        if (!"folder".equals(folder.getFileType())) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Only folders can be downloaded as ZIP");
        }
        FolderStats stats = new FolderStats();
        collectStats(folder, stats);
    }

    @Override
    public void writeZip(FileEntity folder, OutputStream outputStream) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(outputStream)) {
            Set<String> writtenDirectories = new HashSet<>();
            String rootPath = normalizeEntryName(folder.getFileName()) + "/";
            writeDirectoryEntry(zip, writtenDirectories, rootPath);
            writeChildren(folder, rootPath, zip, writtenDirectories);
            zip.finish();
        }
    }

    @Override
    public FolderZipDownloadCacheEntity getOrCreateCachedZip(FileEntity folder) {
        validateDownloadable(folder);

        String signature = computeContentSignature(folder);
        FolderZipDownloadCacheEntity existing = cacheRepository
                .findByUserIdAndFolderId(folder.getUserId(), folder.getId())
                .orElse(null);
        if (existing != null && signature.equals(existing.getContentSignature())
                && storageService.objectExists(existing.getObjectKey())) {
            markDownloaded(existing);
            return existing;
        }

        if (existing != null) {
            storageService.deleteObject(existing.getObjectKey());
        }

        Path zipPath = writeZipTempFile(folder);
        long zipSize = zipPath.toFile().length();
        String objectKey = "folder-zips/" + folder.getUserId() + "/" + folder.getId() + "/"
                + UUID.randomUUID().toString().replace("-", "") + ".zip";
        try (InputStream inputStream = new FileInputStream(zipPath.toFile())) {
            storageService.uploadObject(objectKey, inputStream, zipSize, "application/zip");
        } catch (IOException e) {
            throw new BusinessException(ApiCode.DOWNLOAD_FAILED, "Failed to cache folder ZIP");
        } finally {
            try {
                Files.deleteIfExists(zipPath);
            } catch (IOException ignored) {
            }
        }

        LocalDateTime now = LocalDateTime.now();
        FolderZipDownloadCacheEntity cache = existing != null ? existing : new FolderZipDownloadCacheEntity();
        cache.setUserId(folder.getUserId());
        cache.setFolderId(folder.getId());
        cache.setObjectKey(objectKey);
        cache.setFileName(folder.getFileName() + ".zip");
        cache.setFileSize(zipSize);
        cache.setContentSignature(signature);
        if (cache.getCreatedAt() == null) {
            cache.setCreatedAt(now);
        }
        cache.setLastDownloadedAt(now);
        return cacheRepository.save(cache);
    }

    @Override
    public void markDownloaded(FolderZipDownloadCacheEntity cache) {
        cache.setLastDownloadedAt(LocalDateTime.now());
        cacheRepository.save(cache);
    }

    private void collectStats(FileEntity folder, FolderStats stats) {
        List<FileEntity> children = fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(
                folder.getUserId(), folder.getId());
        for (FileEntity child : children) {
            if ("folder".equals(child.getFileType())) {
                collectStats(child, stats);
            } else if ("file".equals(child.getFileType())) {
                stats.fileCount++;
                if (stats.fileCount > properties.getMaxFiles()) {
                    throw new BusinessException(ApiCode.FOLDER_DOWNLOAD_LIMIT_EXCEEDED,
                            "文件夹内文件数量超过网页下载限制");
                }
                stats.totalSize += child.getFileSize() != null ? child.getFileSize() : 0L;
                if (stats.totalSize > properties.getMaxSizeBytes()) {
                    throw new BusinessException(ApiCode.FOLDER_DOWNLOAD_LIMIT_EXCEEDED,
                            "文件夹总大小超过网页下载限制");
                }
            }
        }
    }

    private Path writeZipTempFile(FileEntity folder) {
        try {
            Path tempFile = Files.createTempFile("bcd-folder-zip-", ".zip");
            try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
                writeZip(folder, outputStream);
            }
            return tempFile;
        } catch (IOException e) {
            throw new BusinessException(ApiCode.DOWNLOAD_FAILED, "Failed to generate folder ZIP");
        }
    }

    private String computeContentSignature(FileEntity folder) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateSignature(digest, folder);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void updateSignature(MessageDigest digest, FileEntity node) {
        appendSignaturePart(digest, node.getId());
        appendSignaturePart(digest, node.getFileName());
        appendSignaturePart(digest, node.getFileType());
        appendSignaturePart(digest, node.getFileSize());
        appendSignaturePart(digest, node.getMd5Hash());
        appendSignaturePart(digest, node.getUpdatedAt());

        if (!"folder".equals(node.getFileType())) {
            return;
        }
        List<FileEntity> children = fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(
                node.getUserId(), node.getId());
        for (FileEntity child : children) {
            updateSignature(digest, child);
        }
    }

    private void appendSignaturePart(MessageDigest digest, Object value) {
        digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private void writeChildren(FileEntity folder, String parentPath, ZipOutputStream zip,
                               Set<String> writtenDirectories) throws IOException {
        List<FileEntity> children = fileRepository.findByUserIdAndParentIdAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(
                folder.getUserId(), folder.getId());
        for (FileEntity child : children) {
            String entryPath = parentPath + normalizeEntryName(child.getFileName());
            if ("folder".equals(child.getFileType())) {
                String directoryPath = entryPath + "/";
                writeDirectoryEntry(zip, writtenDirectories, directoryPath);
                writeChildren(child, directoryPath, zip, writtenDirectories);
            } else if ("file".equals(child.getFileType())) {
                ZipEntry entry = new ZipEntry(entryPath);
                zip.putNextEntry(entry);
                try (InputStream inputStream = storageService.downloadObject(child.getStoragePath())) {
                    inputStream.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }

    private void writeDirectoryEntry(ZipOutputStream zip, Set<String> writtenDirectories, String directoryPath)
            throws IOException {
        if (!writtenDirectories.add(directoryPath)) {
            return;
        }
        zip.putNextEntry(new ZipEntry(directoryPath));
        zip.closeEntry();
    }

    private String normalizeEntryName(String name) {
        String normalized = name == null || name.isBlank() ? "未命名" : name.trim();
        normalized = normalized.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replace("../", "").replace("..", "");
        return normalized.isBlank() ? "未命名" : normalized;
    }

    private static class FolderStats {
        private long fileCount;
        private long totalSize;
    }
}
