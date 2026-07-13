package com.betterclouddrive.dal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
@Table(name = "team_tasks")
public class TeamTaskEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "task_type", nullable = false)
    private String taskType;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "target_type")
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "title_key", nullable = false)
    private String titleKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "title_params", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> titleParams = Map.of();

    @Column(name = "payload")
    private String payload;

    @Column(name = "progress", nullable = false)
    private Integer progress;

    @Column(name = "result_message_key")
    private String resultMessageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_message_params", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> resultMessageParams = Map.of();

    @Column(name = "result_path")
    private String resultPath;

    @Column(name = "result_file_name")
    private String resultFileName;

    @Column(name = "result_content_type")
    private String resultContentType;

    @Column(name = "result_size_bytes")
    private Long resultSizeBytes;

    @Column(name = "result_expires_at")
    private LocalDateTime resultExpiresAt;

    @Column(name = "error_message_key")
    private String errorMessageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_message_params", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> errorMessageParams = Map.of();

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
