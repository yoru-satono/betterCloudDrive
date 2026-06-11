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
@Table(name = "file_versions")
public class FileVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "version_number")
    private Integer versionNumber;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "md5_hash")
    private String md5Hash;

    @Column(name = "storage_path")
    private String storagePath;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
