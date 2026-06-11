package com.betterclouddrive.web.webdav;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class WebDavPathResolver {

    private final FileRepository fileRepository;

    /** Returns the FileEntity at the given WebDAV path, or null if not found. */
    public FileEntity resolve(Long userId, String davPath) {
        List<String> segments = splitPath(davPath);
        if (segments.isEmpty()) return null;

        Long parentId = null;
        FileEntity current = null;
        for (String name : segments) {
            if (parentId == null) {
                current = fileRepository.findRootByName(userId, name).orElse(null);
            } else {
                current = fileRepository
                        .findByUserIdAndParentIdAndFileNameAndIsDeletedFalse(userId, parentId, name)
                        .orElse(null);
            }
            if (current == null) return null;
            parentId = current.getId();
        }
        return current;
    }

    /**
     * Returns the parentId of the given path's parent directory.
     * Returns null for paths directly under root (e.g., "/file.txt").
     */
    public Long resolveParentId(Long userId, String davPath) {
        List<String> segments = splitPath(davPath);
        if (segments.size() <= 1) return null;

        Long parentId = null;
        for (String name : segments.subList(0, segments.size() - 1)) {
            FileEntity folder;
            if (parentId == null) {
                folder = fileRepository.findRootByName(userId, name).orElse(null);
            } else {
                folder = fileRepository
                        .findByUserIdAndParentIdAndFileNameAndIsDeletedFalse(userId, parentId, name)
                        .orElse(null);
            }
            if (folder == null) return null;
            parentId = folder.getId();
        }
        return parentId;
    }

    /** Returns the last segment of the path (the file/folder name). */
    public String extractFileName(String davPath) {
        List<String> segments = splitPath(davPath);
        return segments.isEmpty() ? "" : segments.get(segments.size() - 1);
    }

    private List<String> splitPath(String davPath) {
        if (davPath == null || davPath.isEmpty() || davPath.equals("/")) return List.of();
        String[] parts = davPath.split("/");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String decoded = URLDecoder.decode(part, StandardCharsets.UTF_8).trim();
            if (!decoded.isEmpty()) result.add(decoded);
        }
        return result;
    }
}
