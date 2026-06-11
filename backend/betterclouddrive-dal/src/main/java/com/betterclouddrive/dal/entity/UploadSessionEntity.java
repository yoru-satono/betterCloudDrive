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
@Table(name = "upload_sessions")
public class UploadSessionEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "md5_hash")
    private String md5Hash;

    @Column(name = "chunk_size")
    @Builder.Default
    private Integer chunkSize = 5242880;

    @Column(name = "total_chunks")
    private Integer totalChunks;

    @Column(name = "received_chunks")
    @Builder.Default
    private Integer receivedChunks = 0;

    @Column(name = "storage_path")
    private String storagePath;

    @Column(name = "status")
    @Builder.Default
    private Integer status = 1;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
