package com.betterclouddrive.dal.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username")
    private String username;

    @Column(name = "password_hash")
    @JsonIgnore
    private String passwordHash;

    @Column(name = "webdav_enabled")
    @Builder.Default
    private Boolean webdavEnabled = false;

    @Column(name = "webdav_password_hash")
    @JsonIgnore
    private String webdavPasswordHash;

    @Column(name = "email")
    private String email;

    @Column(name = "email_verified")
    @Builder.Default
    private Boolean emailVerified = false;

    @Column(name = "nickname")
    private String nickname;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "role")
    @Builder.Default
    private String role = "ROLE_USER";

    @Column(name = "status")
    @Builder.Default
    private Integer status = 1;

    @Column(name = "storage_quota")
    @Builder.Default
    private Long storageQuota = 10737418240L;

    @Column(name = "storage_used")
    @Builder.Default
    private Long storageUsed = 0L;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
