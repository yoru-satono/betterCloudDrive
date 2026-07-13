package com.betterclouddrive.dal.entity;

import jakarta.persistence.*;
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
@Table(name = "notifications")
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "type")
    private String type;

    @Column(name = "title_key")
    private String titleKey;

    @Column(name = "content_key")
    private String contentKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message_params", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> messageParams = Map.of();

    @Column(name = "link")
    private String link;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Transient
    public boolean getIsRead() {
        return readAt != null;
    }
}
