package com.betterclouddrive.dal.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "team_event_outbox")
public class TeamEventOutboxEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type")
    private String aggregateType;

    @Column(name = "aggregate_id")
    private Long aggregateId;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "target_type")
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "payload")
    private String payload;

    @Column(name = "message_key")
    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message_params", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> messageParams = Map.of();

    @Column(name = "status")
    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "available_at")
    private LocalDateTime availableAt;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "last_error")
    private String errorMessage;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
