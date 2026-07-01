package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.TeamInviteEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.service.dto.TeamInviteView;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TeamInviteResponse {

    private Long id;
    private Long teamId;
    private String teamName;
    private String email;
    private String role;
    private String status;
    private Long invitedByUserId;
    private String invitedByUsername;
    private LocalDateTime expiresAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime rejectedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TeamInviteResponse from(TeamInviteEntity invite, TeamEntity team, UserEntity invitedBy) {
        return TeamInviteResponse.builder()
                .id(invite.getId())
                .teamId(invite.getTeamId())
                .teamName(team != null ? team.getName() : null)
                .email(invite.getEmail())
                .role(invite.getRole())
                .status(invite.getStatus())
                .invitedByUserId(invite.getInvitedByUserId())
                .invitedByUsername(invitedBy != null ? invitedBy.getUsername() : null)
                .expiresAt(invite.getExpiresAt())
                .acceptedAt(invite.getAcceptedAt())
                .rejectedAt(invite.getRejectedAt())
                .createdAt(invite.getCreatedAt())
                .updatedAt(invite.getUpdatedAt())
                .build();
    }

    public static TeamInviteResponse from(TeamInviteView view) {
        return from(view.invite(), view.team(), view.invitedBy());
    }
}
