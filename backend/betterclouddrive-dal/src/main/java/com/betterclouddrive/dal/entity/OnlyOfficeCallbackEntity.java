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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "onlyoffice_callbacks")
public class OnlyOfficeCallbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "callback_key")
    private String callbackKey;

    @Column(name = "status")
    private Integer status;

    @Column(name = "content_url_hash")
    private String contentUrlHash;

    @Column(name = "download_url")
    private String downloadUrl;

    @Column(name = "state")
    private Integer state;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "version_id")
    private Long versionId;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
