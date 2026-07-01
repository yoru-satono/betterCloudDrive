package com.betterclouddrive.service.dto;

public record TeamEffectivePermission(
        boolean canRead,
        boolean canWrite,
        boolean canShare,
        boolean canDelete,
        boolean canManage) {

    public static TeamEffectivePermission all() {
        return new TeamEffectivePermission(true, true, true, true, true);
    }

    public static TeamEffectivePermission editor() {
        return new TeamEffectivePermission(true, true, true, true, false);
    }

    public static TeamEffectivePermission viewer() {
        return new TeamEffectivePermission(true, false, false, false, false);
    }
}
