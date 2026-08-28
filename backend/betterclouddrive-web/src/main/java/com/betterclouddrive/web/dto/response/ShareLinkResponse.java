package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.ShareLinkEntity;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Data
@Builder
public class ShareLinkResponse {
    private Long id;
    private Long userId;
    private Long teamId;
    private Long fileId;
    private String shareCode;
    private String shareUrl;
    private boolean hasPassword;
    private LocalDateTime expireAt;
    private Long expireAtEpochMs;
    private Integer maxVisits;
    private Integer downloadCount;
    private Integer visitCount;
    private Boolean isCanceled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String fileName;
    private String fileType;
    private Long fileSize;

    public static ShareLinkResponse from(ShareLinkEntity share) {
        return from(share, null, null);
    }

    public static ShareLinkResponse from(ShareLinkEntity share, FileEntity file, String shareUrl) {
        return ShareLinkResponse.builder()
                .id(share.getId())
                .userId(share.getUserId())
                .teamId(share.getTeamId())
                .fileId(share.getFileId())
                .shareCode(share.getShareCode())
                .shareUrl(shareUrl)
                .hasPassword(share.getPasswordCiphertext() != null)
                .expireAt(share.getExpireAt())
                .expireAtEpochMs(share.getExpireAt() == null ? null : share.getExpireAt()
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .maxVisits(share.getMaxVisits())
                .downloadCount(share.getDownloadCount())
                .visitCount(share.getVisitCount())
                .isCanceled(share.getIsCanceled())
                .createdAt(share.getCreatedAt())
                .updatedAt(share.getUpdatedAt())
                .fileName(file == null ? null : file.getFileName())
                .fileType(file == null ? null : file.getFileType())
                .fileSize(file == null ? null : file.getFileSize())
                .build();
    }
}
