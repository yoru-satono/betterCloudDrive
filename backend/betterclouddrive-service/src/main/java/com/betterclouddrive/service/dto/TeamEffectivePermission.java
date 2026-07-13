package com.betterclouddrive.service.dto;

import java.util.List;
import java.util.Map;

public record TeamEffectivePermission(
        boolean canRead,
        boolean canWrite,
        boolean canShare,
        boolean canDelete,
        boolean canManage,
        String sourceType,
        List<PermissionSource> sourceChain) {

    public TeamEffectivePermission(
            boolean canRead,
            boolean canWrite,
            boolean canShare,
            boolean canDelete,
            boolean canManage) {
        this(canRead, canWrite, canShare, canDelete, canManage, "UNSPECIFIED", List.of());
    }

    public TeamEffectivePermission {
        sourceChain = sourceChain == null ? List.of() : List.copyOf(sourceChain);
    }

    public static TeamEffectivePermission all() {
        return new TeamEffectivePermission(true, true, true, true, true);
    }

    public static TeamEffectivePermission editor() {
        return new TeamEffectivePermission(true, true, true, true, false);
    }

    public static TeamEffectivePermission viewer() {
        return new TeamEffectivePermission(true, false, false, false, false);
    }

    public record PermissionSource(
            String type,
            Long fileId,
            String fileName,
            String subjectType,
            Long subjectUserId,
            String subjectRole,
            boolean inherited,
            boolean canRead,
            boolean canWrite,
            boolean canShare,
            boolean canDelete,
            boolean canManage,
            String descriptionKey,
            Map<String, Object> descriptionParams) {
    }
}
