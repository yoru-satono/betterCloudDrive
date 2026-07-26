package com.betterclouddrive.dal.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "dead_letter_records")
public class DeadLetterRecordEntity {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_REPLAYING = "REPLAYING";
    public static final String STATUS_REPLAYED = "REPLAYED";
    public static final String STATUS_DISCARDED = "DISCARDED";

    @Id private UUID id;
    @Column(name = "original_message_id", nullable = false) private String originalMessageId;
    @Column(name = "original_queue", nullable = false) private String originalQueue;
    @Column(name = "routing_key", nullable = false) private String routingKey;
    @Column(name = "task_type", nullable = false) private String taskType;
    @Column(name = "failure_category", nullable = false) private String failureCategory;
    @Column(name = "delivery_count", nullable = false) private Integer deliveryCount;
    @Column(name = "first_failed_at", nullable = false) private LocalDateTime firstFailedAt;
    @Column(name = "last_failed_at", nullable = false) private LocalDateTime lastFailedAt;
    @Column(name = "request_id") private String requestId;
    @Column(name = "trace_id") private String traceId;
    private String traceparent;
    private String tracestate;
    @Column(name = "test_marker") private String testMarker;
    @Column(nullable = false) private String payload;
    @Column(nullable = false) private String status;
    @Column(name = "replay_token") private UUID replayToken;
    @Column(name = "resolution_reason") private String resolutionReason;
    @Column(name = "resolved_by") private Long resolvedBy;
    @Column(name = "resolved_at") private LocalDateTime resolvedAt;
    @Column(name = "created_at", updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
}
