package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.service.dto.TeamView;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TeamResponse {

    private Long id;
    private String name;
    private Long ownerId;
    private Long storageQuota;
    private Long storageUsed;
    private String role;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TeamResponse from(TeamEntity team, String role) {
        return TeamResponse.builder()
                .id(team.getId())
                .name(team.getName())
                .ownerId(team.getOwnerId())
                .storageQuota(team.getStorageQuota())
                .storageUsed(team.getStorageUsed())
                .role(role)
                .createdAt(team.getCreatedAt())
                .updatedAt(team.getUpdatedAt())
                .build();
    }

    public static TeamResponse from(TeamView view) {
        return from(view.team(), view.role());
    }
}
