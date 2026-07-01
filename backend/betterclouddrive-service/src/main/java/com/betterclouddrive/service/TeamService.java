package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.service.dto.TeamMemberView;
import com.betterclouddrive.service.dto.TeamView;

import java.util.List;

public interface TeamService {

    String ROLE_OWNER = "OWNER";
    String ROLE_ADMIN = "ADMIN";
    String ROLE_EDITOR = "EDITOR";
    String ROLE_VIEWER = "VIEWER";

    List<TeamView> listTeams(Long userId);

    TeamView createTeam(Long userId, String name);

    TeamView getTeam(Long userId, Long teamId);

    TeamView updateTeam(Long userId, Long teamId, String name);

    void deleteTeam(Long userId, Long teamId);

    List<TeamMemberView> listMembers(Long userId, Long teamId);

    TeamMemberView addMember(Long actorUserId, Long teamId, String username, String role);

    TeamMemberView updateMember(Long actorUserId, Long teamId, Long targetUserId, String role);

    void removeMember(Long actorUserId, Long teamId, Long targetUserId);

    void leaveTeam(Long userId, Long teamId);

    TeamView transferOwner(Long ownerUserId, Long teamId, Long targetUserId);

    TeamEntity requireTeam(Long teamId);

    TeamMemberEntity requireRead(Long userId, Long teamId);

    TeamMemberEntity requireWrite(Long userId, Long teamId);

    TeamMemberEntity requireManageMembers(Long userId, Long teamId);

    TeamMemberEntity requireOwner(Long userId, Long teamId);
}
