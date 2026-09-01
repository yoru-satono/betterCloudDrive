package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.FileVersionEntity;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class FileVersionResponse {
    private Long id;
    private Long fileId;
    private Integer versionNumber;
    private Long fileSize;
    private String md5Hash;
    private LocalDateTime createdAt;

    public static FileVersionResponse from(FileVersionEntity version) {
        return FileVersionResponse.builder()
                .id(version.getId())
                .fileId(version.getFileId())
                .versionNumber(version.getVersionNumber())
                .fileSize(version.getFileSize())
                .md5Hash(version.getMd5Hash())
                .createdAt(version.getCreatedAt())
                .build();
    }
}
