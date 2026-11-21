package com.betterclouddrive.common.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Allowlisted business facts only; never add a generic payload or request data. */
public record WarehouseEvent(UUID eventId, String deduplicationKey, Type type, Instant occurredAt,
        Long actorUserId, Long teamId, Long resourceId, UUID attemptId,
        Channel channel, Mode mode, Outcome outcome, long byteCount) {
    public enum Type {
        USER_REGISTERED, LOGIN_SUCCEEDED, USER_ACTIVITY, UPLOAD_COMMITTED,
        DOWNLOAD_STARTED, DOWNLOAD_FINISHED, SHARE_CREATED, SHARE_CANCELED, SHARE_ACCESSED
    }
    public enum Channel { API, WEBDAV }
    public enum Mode { NONE, MULTIPART, INSTANT, STREAM, FILE, VERSION, ZIP, SHARE }
    public enum Outcome { SUCCEEDED, STARTED, FAILED }

    public WarehouseEvent {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(channel);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(outcome);
        if (deduplicationKey == null || deduplicationKey.isBlank() || deduplicationKey.length() > 192 || byteCount < 0) {
            throw new IllegalArgumentException("Invalid warehouse event identity or byte count");
        }
    }
}
