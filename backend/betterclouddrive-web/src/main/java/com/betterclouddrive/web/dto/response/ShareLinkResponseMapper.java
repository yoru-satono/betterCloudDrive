package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.ShareLinkEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ShareLinkResponseMapper {
    private final FileRepository fileRepository;
    private final String appPublicUrl;

    public ShareLinkResponseMapper(FileRepository fileRepository,
                                   @Value("${drive.app-public-url:http://localhost:3000}") String appPublicUrl) {
        this.fileRepository = fileRepository;
        this.appPublicUrl = normalizePublicUrl(appPublicUrl);
    }

    public ShareLinkResponse map(ShareLinkEntity share) {
        FileEntity file = fileRepository.findById(share.getFileId())
                .filter(candidate -> !Boolean.TRUE.equals(candidate.getIsDeleted()))
                .orElse(null);
        return ShareLinkResponse.from(share, file, shareUrl(share.getShareCode()));
    }

    public List<ShareLinkResponse> mapAll(Collection<ShareLinkEntity> shares) {
        if (shares.isEmpty()) return Collections.emptyList();
        Map<Long, FileEntity> files = fileRepository.findAllById(
                        shares.stream().map(ShareLinkEntity::getFileId).distinct().toList())
                .stream()
                .filter(file -> !Boolean.TRUE.equals(file.getIsDeleted()))
                .collect(Collectors.toMap(FileEntity::getId, Function.identity()));
        return shares.stream()
                .map(share -> ShareLinkResponse.from(share, files.get(share.getFileId()), shareUrl(share.getShareCode())))
                .toList();
    }

    private String shareUrl(String shareCode) {
        return appPublicUrl + "/s/" + shareCode;
    }

    private static String normalizePublicUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.isBlank() ? "http://localhost:3000" : normalized;
    }
}
