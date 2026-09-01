package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.FileEntity;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RecycleBinItemResponse {
    private Long id;
    private Long parentId;
    private String fileName;
    private String fileType;
    private String mimeType;
    private Long fileSize;
    private String md5Hash;
    private Integer versionCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public static RecycleBinItemResponse from(FileEntity file) {
        return RecycleBinItemResponse.builder()
                .id(file.getId())
                .parentId(file.getParentId())
                .fileName(file.getFileName())
                .fileType(file.getFileType())
                .mimeType(file.getMimeType())
                .fileSize(file.getFileSize())
                .md5Hash(file.getMd5Hash())
                .versionCount(file.getVersionCount())
                .createdAt(file.getCreatedAt())
                .updatedAt(file.getUpdatedAt())
                .deletedAt(file.getDeletedAt())
                .build();
    }
}
