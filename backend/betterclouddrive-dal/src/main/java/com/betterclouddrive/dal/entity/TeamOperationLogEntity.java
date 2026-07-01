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
@Table(name = "team_operation_logs")
public class TeamOperationLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "action")
    private String action;

    @Column(name = "target_type")
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "result")
    private Integer result;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "details")
    private String details;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
