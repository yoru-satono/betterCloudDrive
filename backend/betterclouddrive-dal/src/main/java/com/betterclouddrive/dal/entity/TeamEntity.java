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
@Table(name = "teams")
public class TeamEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "owner_id")
    private Long ownerId;

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

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Column(name = "archived_by")
    private Long archivedBy;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "purge_after")
    private LocalDateTime purgeAfter;
}
