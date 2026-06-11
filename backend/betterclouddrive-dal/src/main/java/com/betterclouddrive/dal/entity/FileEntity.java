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
@Table(name = "files")
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_type")
    @Builder.Default
    private String fileType = "file";

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "file_size")
    @Builder.Default
    private Long fileSize = 0L;

    @Column(name = "storage_path")
    private String storagePath;

    @Column(name = "md5_hash")
    private String md5Hash;

    @Column(name = "thumbnail_path")
    private String thumbnailPath;

    @Column(name = "is_deleted")
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "version_count")
    @Builder.Default
    private Integer versionCount = 1;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
