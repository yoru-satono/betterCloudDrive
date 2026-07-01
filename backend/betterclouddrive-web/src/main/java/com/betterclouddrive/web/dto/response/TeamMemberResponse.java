package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.service.dto.TeamMemberView;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TeamMemberResponse {

    private Long teamId;
    private Long userId;
    private String username;
    private String email;
    private String role;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TeamMemberResponse from(TeamMemberEntity member, UserEntity user) {
        return TeamMemberResponse.builder()
                .teamId(member.getTeamId())
                .userId(member.getUserId())
                .username(user != null ? user.getUsername() : null)
                .email(user != null ? user.getEmail() : null)
                .role(member.getRole())
                .status(member.getStatus())
                .createdAt(member.getCreatedAt())
                .updatedAt(member.getUpdatedAt())
                .build();
    }

    public static TeamMemberResponse from(TeamMemberView view) {
        return from(view.member(), view.user());
    }
}
