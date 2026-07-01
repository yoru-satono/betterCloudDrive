package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.ShareLinkEntity;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShareLinkResponse {
    private Long id;
    private Long userId;
    private Long teamId;
    private Long fileId;
    private String shareCode;
    private boolean hasPassword;
    private LocalDateTime expireAt;
    private Integer maxVisits;
    private Integer downloadCount;
    private Integer visitCount;
    private Boolean isCanceled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ShareLinkResponse from(ShareLinkEntity share) {
        return ShareLinkResponse.builder()
                .id(share.getId())
                .userId(share.getUserId())
                .teamId(share.getTeamId())
                .fileId(share.getFileId())
                .shareCode(share.getShareCode())
                .hasPassword(share.getPasswordCiphertext() != null)
                .expireAt(share.getExpireAt())
                .maxVisits(share.getMaxVisits())
                .downloadCount(share.getDownloadCount())
                .visitCount(share.getVisitCount())
                .isCanceled(share.getIsCanceled())
                .createdAt(share.getCreatedAt())
                .updatedAt(share.getUpdatedAt())
                .build();
    }
}
