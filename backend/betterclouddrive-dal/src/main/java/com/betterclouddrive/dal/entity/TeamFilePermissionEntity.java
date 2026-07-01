package com.betterclouddrive.dal.entity;

import jakarta.persistence.*;
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
@Table(name = "team_file_permissions")
public class TeamFilePermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "subject_type")
    private String subjectType;

    @Column(name = "subject_user_id")
    private Long subjectUserId;

    @Column(name = "subject_role")
    private String subjectRole;

    @Column(name = "can_read")
    private Boolean canRead;

    @Column(name = "can_write")
    private Boolean canWrite;

    @Column(name = "can_share")
    private Boolean canShare;

    @Column(name = "can_delete")
    private Boolean canDelete;

    @Column(name = "can_manage")
    private Boolean canManage;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
