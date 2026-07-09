package com.betterclouddrive.service.dto;

public record TeamAuditExportPayload(
        Long requesterUserId,
        Long teamId,
        Long actorUserId,
        String action,
        String targetType,
        Integer result,
        String startAt,
        String endAt,
        int limit) {
}
