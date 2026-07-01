package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.TeamInviteEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.TeamInviteRepository;
import com.betterclouddrive.dal.repository.TeamMemberRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.EmailService;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamInviteService;
import com.betterclouddrive.service.TeamService;
import com.betterclouddrive.service.dto.TeamInviteView;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TeamInviteServiceImpl implements TeamInviteService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELED = "CANCELED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private static final int ACTIVE = 1;
    private static final Set<String> INVITABLE_ROLES = Set.of(
            TeamService.ROLE_ADMIN,
            TeamService.ROLE_EDITOR,
            TeamService.ROLE_VIEWER);

    private final TeamInviteRepository teamInviteRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final TeamService teamService;
    private final EmailService emailService;
    private final NotificationService notificationService;
    private final TeamAuditService teamAuditService;

    @Value("${drive.team.invite-ttl-hours:168}")
    private long inviteTtlHours;

    @Value("${drive.app-public-url:http://localhost:3000}")
    private String appPublicUrl;

    @Override
    @Transactional
    public TeamInviteView createInvite(Long actorUserId, Long teamId, String email, String role) {
        TeamMemberEntity actor = teamService.requireManageMembers(actorUserId, teamId);
        TeamEntity team = teamService.requireTeam(teamId);
        String normalizedEmail = normalizeEmail(email);
        String normalizedRole = normalizeRole(role);
        if (!TeamService.ROLE_OWNER.equals(actor.getRole()) && TeamService.ROLE_ADMIN.equals(normalizedRole)) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Only owner can invite admins");
        }
        UserEntity existingUser = userRepository.findByEmail(normalizedEmail).orElse(null);
        if (existingUser != null && teamMemberRepository.findByTeamIdAndUserIdAndStatus(teamId, existingUser.getId(), ACTIVE).isPresent()) {
            throw new BusinessException(ApiCode.CONFLICT, "User is already a team member");
        }

        LocalDateTime now = LocalDateTime.now();
        TeamInviteEntity invite = teamInviteRepository.findByTeamIdAndEmailAndStatus(teamId, normalizedEmail, STATUS_PENDING)
                .orElseGet(TeamInviteEntity::new);
        invite.setTeamId(teamId);
        invite.setEmail(normalizedEmail);
        invite.setRole(normalizedRole);
        invite.setToken(newToken());
        invite.setStatus(STATUS_PENDING);
        invite.setInvitedByUserId(actorUserId);
        invite.setAcceptedByUserId(null);
        invite.setAcceptedAt(null);
        invite.setRejectedAt(null);
        invite.setExpiresAt(now.plusHours(inviteTtlHours));
        if (invite.getCreatedAt() == null) {
            invite.setCreatedAt(now);
        }
        invite.setUpdatedAt(now);
        TeamInviteEntity saved = teamInviteRepository.save(invite);
        sendInvite(team, saved);
        if (existingUser != null) {
            notificationService.notifyUser(existingUser.getId(), teamId, "TEAM_INVITE", "团队邀请",
                    "你被邀请加入团队「" + team.getName() + "」，角色为 " + normalizedRole + "。",
                    "/team-invites/" + saved.getToken(), false);
        }
        teamAuditService.record(teamId, actorUserId, "INVITE_CREATE", "INVITE", saved.getId(), normalizedEmail + " as " + normalizedRole);
        return view(saved);
    }

    @Override
    public List<TeamInviteView> listInvites(Long actorUserId, Long teamId) {
        teamService.requireManageMembers(actorUserId, teamId);
        return teamInviteRepository.findByTeamIdOrderByCreatedAtDesc(teamId).stream()
                .map(this::view)
                .toList();
    }

    @Override
    @Transactional
    public TeamInviteView resendInvite(Long actorUserId, Long teamId, Long inviteId) {
        teamService.requireManageMembers(actorUserId, teamId);
        TeamInviteEntity invite = requireTeamInvite(teamId, inviteId);
        if (!STATUS_PENDING.equals(invite.getStatus())) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Only pending invites can be resent");
        }
        LocalDateTime now = LocalDateTime.now();
        invite.setToken(newToken());
        invite.setExpiresAt(now.plusHours(inviteTtlHours));
        invite.setUpdatedAt(now);
        TeamInviteEntity saved = teamInviteRepository.save(invite);
        sendInvite(teamService.requireTeam(teamId), saved);
        UserEntity invited = userRepository.findByEmail(saved.getEmail()).orElse(null);
        if (invited != null) {
            notificationService.notifyUser(invited.getId(), teamId, "TEAM_INVITE", "团队邀请已重发",
                    "你的团队邀请已重发。", "/team-invites/" + saved.getToken(), false);
        }
        teamAuditService.record(teamId, actorUserId, "INVITE_RESEND", "INVITE", saved.getId(), saved.getEmail());
        return view(saved);
    }

    @Override
    @Transactional
    public void cancelInvite(Long actorUserId, Long teamId, Long inviteId) {
        teamService.requireManageMembers(actorUserId, teamId);
        TeamInviteEntity invite = requireTeamInvite(teamId, inviteId);
        if (!STATUS_PENDING.equals(invite.getStatus())) {
            return;
        }
        invite.setStatus(STATUS_CANCELED);
        invite.setUpdatedAt(LocalDateTime.now());
        teamInviteRepository.save(invite);
        teamAuditService.record(teamId, actorUserId, "INVITE_CANCEL", "INVITE", inviteId, invite.getEmail());
    }

    @Override
    public TeamInviteView getInviteByToken(String token) {
        TeamInviteEntity invite = requireInviteByToken(token);
        expireIfNeeded(invite);
        return view(invite);
    }

    @Override
    @Transactional
    public TeamInviteEntity acceptInvite(Long userId, String token) {
        TeamInviteEntity invite = requireInviteByToken(token);
        expireIfNeeded(invite);
        if (!STATUS_PENDING.equals(invite.getStatus())) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Invite is not pending");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "User not found"));
        if (!normalizeEmail(user.getEmail()).equals(invite.getEmail())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Invite email does not match current user");
        }
        TeamMemberEntity member = teamMemberRepository.findByTeamIdAndUserId(invite.getTeamId(), userId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (member == null) {
            member = TeamMemberEntity.builder()
                    .teamId(invite.getTeamId())
                    .userId(userId)
                    .role(invite.getRole())
                    .status(ACTIVE)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        } else if (TeamService.ROLE_OWNER.equals(member.getRole())) {
            throw new BusinessException(ApiCode.CONFLICT, "Owner is already a team member");
        } else {
            member.setRole(invite.getRole());
            member.setStatus(ACTIVE);
            member.setUpdatedAt(now);
        }
        teamMemberRepository.save(member);
        invite.setStatus(STATUS_ACCEPTED);
        invite.setAcceptedByUserId(userId);
        invite.setAcceptedAt(now);
        invite.setUpdatedAt(now);
        TeamInviteEntity saved = teamInviteRepository.save(invite);
        teamAuditService.record(invite.getTeamId(), userId, "INVITE_ACCEPT", "INVITE", invite.getId(), invite.getEmail());
        notificationService.notifyUser(invite.getInvitedByUserId(), invite.getTeamId(), "TEAM_INVITE_ACCEPT", "团队邀请已接受",
                user.getUsername() + " 已接受团队邀请。", "/teams/" + invite.getTeamId() + "/settings", true);
        return saved;
    }

    @Override
    @Transactional
    public TeamInviteEntity rejectInvite(Long userId, String token) {
        TeamInviteEntity invite = requireInviteByToken(token);
        expireIfNeeded(invite);
        if (!STATUS_PENDING.equals(invite.getStatus())) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Invite is not pending");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "User not found"));
        if (!normalizeEmail(user.getEmail()).equals(invite.getEmail())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Invite email does not match current user");
        }
        invite.setStatus(STATUS_REJECTED);
        invite.setRejectedAt(LocalDateTime.now());
        invite.setUpdatedAt(LocalDateTime.now());
        TeamInviteEntity saved = teamInviteRepository.save(invite);
        teamAuditService.record(invite.getTeamId(), userId, "INVITE_REJECT", "INVITE", invite.getId(), invite.getEmail());
        notificationService.notifyUser(invite.getInvitedByUserId(), invite.getTeamId(), "TEAM_INVITE_REJECT", "团队邀请已拒绝",
                user.getUsername() + " 已拒绝团队邀请。", "/teams/" + invite.getTeamId() + "/settings", true);
        return saved;
    }

    private TeamInviteEntity requireTeamInvite(Long teamId, Long inviteId) {
        return teamInviteRepository.findByIdAndTeamId(inviteId, teamId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Invite not found"));
    }

    private TeamInviteEntity requireInviteByToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Invite not found");
        }
        return teamInviteRepository.findByToken(token.trim())
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Invite not found"));
    }

    private TeamInviteView view(TeamInviteEntity invite) {
        TeamEntity team = teamService.requireTeam(invite.getTeamId());
        UserEntity invitedBy = userRepository.findById(invite.getInvitedByUserId()).orElse(null);
        return new TeamInviteView(invite, team, invitedBy);
    }

    private void expireIfNeeded(TeamInviteEntity invite) {
        if (STATUS_PENDING.equals(invite.getStatus()) && invite.getExpiresAt().isBefore(LocalDateTime.now())) {
            invite.setStatus(STATUS_EXPIRED);
            invite.setUpdatedAt(LocalDateTime.now());
            teamInviteRepository.save(invite);
        }
    }

    private void sendInvite(TeamEntity team, TeamInviteEntity invite) {
        String link = appPublicUrl.replaceAll("/+$", "") + "/team-invites/" + invite.getToken();
        emailService.sendTeamInvite(invite.getEmail(), team.getName(), invite.getRole(), link, invite.getExpiresAt());
    }

    private String normalizeEmail(String email) {
        String normalized = StringUtils.hasText(email) ? email.trim().toLowerCase() : "";
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Email is required");
        }
        return normalized;
    }

    private String normalizeRole(String role) {
        String normalized = StringUtils.hasText(role) ? role.trim().toUpperCase() : "";
        if (!INVITABLE_ROLES.contains(normalized)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Role must be ADMIN, EDITOR, or VIEWER");
        }
        return normalized;
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
