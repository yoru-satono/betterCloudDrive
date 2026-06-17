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
@Table(name = "share_links")
public class ShareLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "share_code")
    private String shareCode;

    @Column(name = "password_ciphertext")
    private String passwordCiphertext;

    @Column(name = "expire_at")
    private LocalDateTime expireAt;

    @Column(name = "max_visits")
    private Integer maxVisits;

    @Column(name = "download_count")
    @Builder.Default
    private Integer downloadCount = 0;

    @Column(name = "visit_count")
    @Builder.Default
    private Integer visitCount = 0;

    @Column(name = "is_canceled")
    @Builder.Default
    private Boolean isCanceled = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
