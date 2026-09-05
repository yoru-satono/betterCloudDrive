package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.TeamMemberRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamNotificationDispatchService;
import com.betterclouddrive.service.TeamService;
import com.betterclouddrive.service.dto.TeamMemberView;
import com.betterclouddrive.service.dto.TeamView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeamServiceImpl implements TeamService {

    private static final int ACTIVE = 1;
    private static final int REMOVED = 0;
    private static final long DEFAULT_TEAM_QUOTA = 10737418240L;
    private static final long TEAM_DELETE_RETENTION_DAYS = 30L;
    private static final Set<String> MEMBER_ROLES = Set.of(ROLE_ADMIN, ROLE_EDITOR, ROLE_VIEWER);

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final TeamAuditService teamAuditService;
    private final TeamNotificationDispatchService teamNotificationDispatchService;

    @Override
    public PageResult<TeamView> listTeams(Long userId, int page, int size) {
        Page<TeamEntity> result = teamRepository.findActiveTeamsForUser(userId, PageRequest.of(Math.max(page - 1, 0), size));
        List<TeamView> records = result.getContent().stream()
                .map(team -> new TeamView(team, requireRead(userId, team.getId()).getRole()))
                .toList();
        return PageResult.of(records, result.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public TeamView createTeam(Long userId, String name) {
        LocalDateTime now = LocalDateTime.now();
        TeamEntity team = TeamEntity.builder()
                .name(normalizeName(name))
                .ownerId(userId)
                .storageQuota(DEFAULT_TEAM_QUOTA)
                .storageUsed(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        team = teamRepository.save(team);

        TeamMemberEntity owner = TeamMemberEntity.builder()
                .teamId(team.getId())
                .userId(userId)
                .role(ROLE_OWNER)
                .status(ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        teamMemberRepository.save(owner);
        teamAuditService.record(team.getId(), userId, "TEAM_CREATE", "TEAM", team.getId(), team.getName());
        return new TeamView(team, ROLE_OWNER);
    }

    @Override
    public TeamView getTeam(Long userId, Long teamId) {
        TeamEntity team = requireTeam(teamId);
        TeamMemberEntity member = requireRead(userId, teamId);
        return new TeamView(team, member.getRole());
    }

    @Override
    @Transactional
    public TeamView updateTeam(Long userId, Long teamId, String name) {
        requireManageMembers(userId, teamId);
        TeamEntity team = requireTeam(teamId);
        team.setName(normalizeName(name));
        team.setUpdatedAt(LocalDateTime.now());
        TeamEntity saved = teamRepository.save(team);
        teamAuditService.record(teamId, userId, "TEAM_UPDATE", "TEAM", teamId, saved.getName());
        return new TeamView(saved, requireRead(userId, teamId).getRole());
    }

    @Override
    @Transactional
    public void deleteTeam(Long userId, Long teamId) {
        TeamEntity team = teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Team not found"));
        TeamMemberEntity owner = teamMemberRepository.findByTeamIdAndUserIdAndStatus(teamId, userId, ACTIVE)
                .orElseThrow(() -> new BusinessException(ApiCode.FORBIDDEN, "Not a team member"));
        if (!ROLE_OWNER.equals(owner.getRole())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Only owner can perform this operation");
        }
        if (team.getArchivedAt() != null && team.getDeletedAt() == null) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Team not found");
        }
        if (team.getDeletedAt() != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        team.setDeletedAt(now);
        team.setDeletedBy(userId);
        team.setPurgeAfter(now.plusDays(TEAM_DELETE_RETENTION_DAYS));
        team.setUpdatedAt(now);
        teamRepository.save(team);
        teamAuditService.record(teamId, userId, "TEAM_DELETE", "TEAM", teamId, team.getName());
        teamNotificationDispatchService.enqueueTeamMembersNotification(teamId, userId, "TEAM_DELETE", "Team deleted",
                "The team was deleted by its owner", null, true);
    }

    @Override
    public PageResult<TeamMemberView> listMembers(Long userId, Long teamId, int page, int size) {
        requireRead(userId, teamId);
        Page<TeamMemberEntity> memberPage = teamMemberRepository.findActiveMembersStable(
                teamId, ACTIVE, PageRequest.of(Math.max(page - 1, 0), size));
        List<TeamMemberEntity> members = memberPage.getContent();
        Map<Long, UserEntity> users = userRepository.findAllById(
                        members.stream().map(TeamMemberEntity::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        List<TeamMemberView> records = members.stream()
                .map(member -> new TeamMemberView(member, users.get(member.getUserId())))
                .toList();
        return PageResult.of(records, memberPage.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public TeamMemberView addMember(Long actorUserId, Long teamId, String username, String role) {
        TeamMemberEntity actor = requireManageMembers(actorUserId, teamId);
        requireTeam(teamId);
        String normalizedRole = normalizeMemberRole(role);
        if (!ROLE_OWNER.equals(actor.getRole()) && ROLE_ADMIN.equals(normalizedRole)) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Only owner can add admins");
        }
        UserEntity user = userRepository.findByUsername(username.trim())
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "User not found"));
        TeamMemberEntity member = teamMemberRepository.findByTeamIdAndUserId(teamId, user.getId()).orElse(null);
        if (member != null && Objects.equals(member.getStatus(), ACTIVE) &&
                !ROLE_OWNER.equals(actor.getRole()) && ROLE_ADMIN.equals(member.getRole())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Only owner can manage admins");
        }
        LocalDateTime now = LocalDateTime.now();
        if (member == null) {
            member = TeamMemberEntity.builder()
                    .teamId(teamId)
                    .userId(user.getId())
                    .role(normalizedRole)
                    .status(ACTIVE)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        } else if (ROLE_OWNER.equals(member.getRole())) {
            throw new BusinessException(ApiCode.CONFLICT, "Owner is already a team member");
        } else {
            member.setRole(normalizedRole);
            member.setStatus(ACTIVE);
            member.setUpdatedAt(now);
        }
        TeamMemberEntity saved = teamMemberRepository.save(member);
        teamAuditService.record(teamId, actorUserId, "MEMBER_ADD", "USER", user.getId(), user.getUsername() + " as " + normalizedRole);
        teamNotificationDispatchService.enqueueUserNotification(user.getId(), teamId, "TEAM_MEMBER_ADD", "Added to team",
                "You were added to a team with role " + normalizedRole, "/teams/" + teamId + "/files", true);
        return new TeamMemberView(saved, user);
    }

    @Override
    @Transactional
    public TeamMemberView updateMember(Long actorUserId, Long teamId, Long targetUserId, String role) {
        TeamMemberEntity actor = requireManageMembers(actorUserId, teamId);
        String normalizedRole = normalizeMemberRole(role);
        TeamMemberEntity member = teamMemberRepository.findByTeamIdAndUserIdAndStatus(teamId, targetUserId, ACTIVE)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Team member not found"));
        if (ROLE_OWNER.equals(member.getRole())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Owner role cannot be changed");
        }
        if (!ROLE_OWNER.equals(actor.getRole()) &&
                (ROLE_ADMIN.equals(member.getRole()) || ROLE_ADMIN.equals(normalizedRole))) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Only owner can manage admins");
        }
        member.setRole(normalizedRole);
        member.setUpdatedAt(LocalDateTime.now());
        UserEntity user = userRepository.findById(targetUserId).orElse(null);
        TeamMemberEntity saved = teamMemberRepository.save(member);
        teamAuditService.record(teamId, actorUserId, "MEMBER_ROLE_UPDATE", "USER", targetUserId, normalizedRole);
        teamNotificationDispatchService.enqueueUserNotification(targetUserId, teamId, "TEAM_ROLE_UPDATE", "Team role updated",
                "Your team role is now " + normalizedRole, "/teams/" + teamId + "/settings", true);
        return new TeamMemberView(saved, user);
    }

    @Override
    @Transactional
    public void removeMember(Long actorUserId, Long teamId, Long targetUserId) {
        TeamMemberEntity actor = requireManageMembers(actorUserId, teamId);
        TeamMemberEntity member = teamMemberRepository.findByTeamIdAndUserIdAndStatus(teamId, targetUserId, ACTIVE)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Team member not found"));
        if (ROLE_OWNER.equals(member.getRole())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Owner cannot be removed");
        }
        if (!ROLE_OWNER.equals(actor.getRole()) && ROLE_ADMIN.equals(member.getRole())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Only owner can remove admins");
        }
        member.setStatus(REMOVED);
        member.setUpdatedAt(LocalDateTime.now());
        teamMemberRepository.save(member);
        teamAuditService.record(teamId, actorUserId, "MEMBER_REMOVE", "USER", targetUserId, member.getRole());
        teamNotificationDispatchService.enqueueUserNotification(targetUserId, teamId, "TEAM_MEMBER_REMOVE", "Removed from team",
                "You were removed from a team", null, true);
    }

    @Override
    @Transactional
    public void leaveTeam(Long userId, Long teamId) {
        TeamMemberEntity member = requireRead(userId, teamId);
        if (ROLE_OWNER.equals(member.getRole())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Owner must transfer ownership before leaving");
        }
        member.setStatus(REMOVED);
        member.setUpdatedAt(LocalDateTime.now());
        teamMemberRepository.save(member);
        teamAuditService.record(teamId, userId, "MEMBER_LEAVE", "USER", userId, null);
    }

    @Override
    @Transactional
    public TeamView transferOwner(Long ownerUserId, Long teamId, Long targetUserId) {
        TeamMemberEntity owner = requireOwner(ownerUserId, teamId);
        if (ownerUserId.equals(targetUserId)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Target user is already owner");
        }
        TeamMemberEntity target = teamMemberRepository.findByTeamIdAndUserIdAndStatus(teamId, targetUserId, ACTIVE)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Team member not found"));
        TeamEntity team = requireTeam(teamId);
        LocalDateTime now = LocalDateTime.now();
        owner.setRole(ROLE_ADMIN);
        owner.setUpdatedAt(now);
        target.setRole(ROLE_OWNER);
        target.setUpdatedAt(now);
        team.setOwnerId(targetUserId);
        team.setUpdatedAt(now);
        teamMemberRepository.save(owner);
        teamMemberRepository.save(target);
        TeamEntity savedTeam = teamRepository.save(team);
        teamAuditService.record(teamId, ownerUserId, "OWNER_TRANSFER", "USER", targetUserId, null);
        teamNotificationDispatchService.enqueueUserNotification(targetUserId, teamId, "TEAM_OWNER_TRANSFER", "Team ownership transferred",
                "You are now the team owner", "/teams/" + teamId + "/settings", true);
        return new TeamView(savedTeam, ROLE_ADMIN);
    }

    @Override
    public TeamEntity requireTeam(Long teamId) {
        TeamEntity team = teamRepository.findByIdAndDeletedAtIsNull(teamId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Team not found"));
        if (team.getArchivedAt() != null) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Team not found");
        }
        return team;
    }

    @Override
    public TeamMemberEntity requireRead(Long userId, Long teamId) {
        requireTeam(teamId);
        return teamMemberRepository.findByTeamIdAndUserIdAndStatus(teamId, userId, ACTIVE)
                .orElseThrow(() -> new BusinessException(ApiCode.FORBIDDEN, "Not a team member"));
    }

    @Override
    public TeamMemberEntity requireWrite(Long userId, Long teamId) {
        TeamMemberEntity member = requireRead(userId, teamId);
        if (!Set.of(ROLE_OWNER, ROLE_ADMIN, ROLE_EDITOR).contains(member.getRole())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Team role cannot write files");
        }
        return member;
    }

    @Override
    public TeamMemberEntity requireManageMembers(Long userId, Long teamId) {
        TeamMemberEntity member = requireRead(userId, teamId);
        if (!Set.of(ROLE_OWNER, ROLE_ADMIN).contains(member.getRole())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Team role cannot manage members");
        }
        return member;
    }

    @Override
    public TeamMemberEntity requireOwner(Long userId, Long teamId) {
        TeamMemberEntity member = requireRead(userId, teamId);
        if (!ROLE_OWNER.equals(member.getRole())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Only owner can perform this operation");
        }
        return member;
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank() || normalized.length() > 80) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Team name must be 1-80 characters");
        }
        return normalized;
    }

    private String normalizeMemberRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase();
        if (!MEMBER_ROLES.contains(normalized)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Role must be ADMIN, EDITOR, or VIEWER");
        }
        return normalized;
    }
}
