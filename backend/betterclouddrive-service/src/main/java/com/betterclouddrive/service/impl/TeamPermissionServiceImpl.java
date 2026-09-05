package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TeamFilePermissionEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.TeamFilePermissionRepository;
import com.betterclouddrive.dal.repository.TeamMemberRepository;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamNotificationDispatchService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.TeamService;
import com.betterclouddrive.service.dto.TeamEffectivePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TeamPermissionServiceImpl implements TeamPermissionService {

    private static final String SUBJECT_USER = "USER";
    private static final String SUBJECT_ROLE = "ROLE";

    private final TeamService teamService;
    private final FileRepository fileRepository;
    private final TeamFilePermissionRepository permissionRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamAuditService teamAuditService;
    private final TeamNotificationDispatchService teamNotificationDispatchService;

    @Override
    public TeamEffectivePermission effectivePermissions(Long userId, Long teamId, Long fileId) {
        TeamMemberEntity member = teamService.requireRead(userId, teamId);
        TeamEffectivePermission base = basePermissions(member.getRole());
        List<TeamEffectivePermission.PermissionSource> sourceChain = new ArrayList<>();
        sourceChain.add(baseSource(member.getRole(), base));
        if (fileId == null || TeamService.ROLE_OWNER.equals(member.getRole())) {
            return withSources(base, "BASE_ROLE", sourceChain);
        }
        FileEntity file = requireTeamFileAnyState(teamId, fileId);
        if (!Boolean.TRUE.equals(file.getIsDeleted()) && !hasActiveAncestors(teamId, file)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        Set<Long> seen = new HashSet<>();
        FileEntity cursor = file;
        while (cursor != null && seen.add(cursor.getId())) {
            TeamFilePermissionEntity userOverride = permissionRepository
                    .findByTeamIdAndFileIdAndSubjectTypeAndSubjectUserId(teamId, cursor.getId(), SUBJECT_USER, userId)
                    .orElse(null);
            if (userOverride != null) {
                return fromEntity(userOverride, cursor, !Objects.equals(cursor.getId(), fileId), sourceChain);
            }
            TeamFilePermissionEntity roleOverride = permissionRepository
                    .findByTeamIdAndFileIdAndSubjectTypeAndSubjectRole(teamId, cursor.getId(), SUBJECT_ROLE, member.getRole())
                    .orElse(null);
            if (roleOverride != null) {
                return fromEntity(roleOverride, cursor, !Objects.equals(cursor.getId(), fileId), sourceChain);
            }
            cursor = cursor.getParentId() != null
                    ? fileRepository.findById(cursor.getParentId()).orElse(null)
                    : null;
            if (cursor != null && (!Objects.equals(cursor.getTeamId(), teamId) || Boolean.TRUE.equals(cursor.getIsDeleted()))) {
                cursor = null;
            }
        }
        return withSources(base, "BASE_ROLE", sourceChain);
    }

    @Override
    public List<TeamFilePermissionEntity> listPermissions(Long actorUserId, Long teamId, Long fileId) {
        requireManage(actorUserId, teamId, fileId);
        return permissionRepository.findByTeamIdAndFileIdOrderByIdAsc(teamId, fileId);
    }

    @Override
    @Transactional
    public TeamFilePermissionEntity upsertPermission(
            Long actorUserId,
            Long teamId,
            Long fileId,
            String subjectType,
            Long subjectUserId,
            String subjectRole,
            boolean canRead,
            boolean canWrite,
            boolean canShare,
            boolean canDelete,
            boolean canManage) {
        requireManage(actorUserId, teamId, fileId);
        validatePermissionConsistency(canRead, canWrite, canShare, canDelete, canManage);
        FileEntity file = requireTeamFileAnyState(teamId, fileId);
        String normalizedType = normalizeSubjectType(subjectType);
        TeamFilePermissionEntity permission;
        if (SUBJECT_USER.equals(normalizedType)) {
            if (subjectUserId == null) {
                throw new BusinessException(ApiCode.BAD_REQUEST, "subjectUserId is required");
            }
            TeamMemberEntity subjectMember = teamService.requireRead(subjectUserId, teamId);
            if (TeamService.ROLE_OWNER.equals(subjectMember.getRole())) {
                throw new BusinessException(ApiCode.BAD_REQUEST, "Owner permissions cannot be overridden");
            }
            if (actorUserId.equals(subjectUserId) && !canManage) {
                throw new BusinessException(ApiCode.BAD_REQUEST, "Cannot remove your own manage permission");
            }
            permission = permissionRepository
                    .findByTeamIdAndFileIdAndSubjectTypeAndSubjectUserId(teamId, fileId, SUBJECT_USER, subjectUserId)
                    .orElseGet(TeamFilePermissionEntity::new);
            permission.setSubjectUserId(subjectUserId);
            permission.setSubjectRole(null);
        } else {
            String normalizedRole = normalizeRole(subjectRole);
            TeamMemberEntity actor = teamService.requireRead(actorUserId, teamId);
            if (normalizedRole.equals(actor.getRole()) && !canManage) {
                throw new BusinessException(ApiCode.BAD_REQUEST, "Cannot remove your own role manage permission");
            }
            permission = permissionRepository
                    .findByTeamIdAndFileIdAndSubjectTypeAndSubjectRole(teamId, fileId, SUBJECT_ROLE, normalizedRole)
                    .orElseGet(TeamFilePermissionEntity::new);
            permission.setSubjectRole(normalizedRole);
            permission.setSubjectUserId(null);
        }
        LocalDateTime now = LocalDateTime.now();
        if (permission.getCreatedAt() == null) {
            permission.setCreatedAt(now);
            permission.setCreatedBy(actorUserId);
        }
        permission.setTeamId(teamId);
        permission.setFileId(fileId);
        permission.setSubjectType(normalizedType);
        permission.setCanRead(canRead);
        permission.setCanWrite(canWrite);
        permission.setCanShare(canShare);
        permission.setCanDelete(canDelete);
        permission.setCanManage(canManage);
        permission.setUpdatedAt(now);
        TeamFilePermissionEntity saved = permissionRepository.saveAndFlush(permission);
        if (!effectivePermissions(actorUserId, teamId, fileId).canManage()) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Permission change would remove your own manage permission");
        }
        teamAuditService.record(teamId, actorUserId, "PERMISSION_UPDATE", "FILE", fileId,
                normalizedType + ":" + (SUBJECT_USER.equals(normalizedType) ? subjectUserId : permission.getSubjectRole()));
        notifyPermissionChange(teamId, actorUserId, file, saved, "Team file permission updated");
        return saved;
    }

    @Override
    @Transactional
    public void deletePermission(Long actorUserId, Long teamId, Long fileId, Long permissionId) {
        requireManage(actorUserId, teamId, fileId);
        TeamFilePermissionEntity permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Permission not found"));
        if (!Objects.equals(permission.getTeamId(), teamId) || !Objects.equals(permission.getFileId(), fileId)) {
            throw new BusinessException(ApiCode.NOT_FOUND, "Permission not found");
        }
        permissionRepository.delete(permission);
        permissionRepository.flush();
        if (!effectivePermissions(actorUserId, teamId, fileId).canManage()) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Permission change would remove your own manage permission");
        }
        teamAuditService.record(teamId, actorUserId, "PERMISSION_DELETE", "FILE", fileId, String.valueOf(permissionId));
        notifyPermissionChange(teamId, actorUserId, requireTeamFileAnyState(teamId, fileId), permission, "Team file permission removed");
    }

    @Override
    public void requireRead(Long userId, Long teamId, Long fileId) {
        if (!effectivePermissions(userId, teamId, fileId).canRead()) {
            throw new BusinessException(ApiCode.FORBIDDEN, "No read permission for this team file");
        }
    }

    @Override
    public void requireWrite(Long userId, Long teamId, Long fileId) {
        if (!effectivePermissions(userId, teamId, fileId).canWrite()) {
            throw new BusinessException(ApiCode.FORBIDDEN, "No write permission for this team file");
        }
    }

    @Override
    public void requireShare(Long userId, Long teamId, Long fileId) {
        if (!effectivePermissions(userId, teamId, fileId).canShare()) {
            throw new BusinessException(ApiCode.FORBIDDEN, "No share permission for this team file");
        }
    }

    @Override
    public void requireDelete(Long userId, Long teamId, Long fileId) {
        if (!effectivePermissions(userId, teamId, fileId).canDelete()) {
            throw new BusinessException(ApiCode.FORBIDDEN, "No delete permission for this team file");
        }
    }

    @Override
    public void requireManage(Long userId, Long teamId, Long fileId) {
        if (!effectivePermissions(userId, teamId, fileId).canManage()) {
            throw new BusinessException(ApiCode.FORBIDDEN, "No manage permission for this team file");
        }
    }

    @Override
    public void requireParentWrite(Long userId, Long teamId, Long parentId) {
        if (parentId == null) {
            teamService.requireWrite(userId, teamId);
            return;
        }
        requireWrite(userId, teamId, parentId);
    }

    private TeamEffectivePermission basePermissions(String role) {
        if (TeamService.ROLE_OWNER.equals(role) || TeamService.ROLE_ADMIN.equals(role)) {
            return TeamEffectivePermission.all();
        }
        if (TeamService.ROLE_EDITOR.equals(role)) {
            return TeamEffectivePermission.editor();
        }
        return TeamEffectivePermission.viewer();
    }

    private void validatePermissionConsistency(
            boolean canRead,
            boolean canWrite,
            boolean canShare,
            boolean canDelete,
            boolean canManage) {
        if (!canRead && (canWrite || canShare || canDelete || canManage)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "write/share/delete/manage permissions require read permission");
        }
    }

    private TeamEffectivePermission fromEntity(TeamFilePermissionEntity permission) {
        return new TeamEffectivePermission(
                Boolean.TRUE.equals(permission.getCanRead()),
                Boolean.TRUE.equals(permission.getCanWrite()),
                Boolean.TRUE.equals(permission.getCanShare()),
                Boolean.TRUE.equals(permission.getCanDelete()),
                Boolean.TRUE.equals(permission.getCanManage()));
    }

    private TeamEffectivePermission fromEntity(
            TeamFilePermissionEntity permission,
            FileEntity sourceFile,
            boolean inherited,
            List<TeamEffectivePermission.PermissionSource> sourceChain) {
        TeamEffectivePermission effective = fromEntity(permission);
        List<TeamEffectivePermission.PermissionSource> resolvedChain = new ArrayList<>(sourceChain);
        resolvedChain.add(overrideSource(permission, sourceFile, inherited));
        String sourceType = SUBJECT_USER.equals(permission.getSubjectType()) ? "USER_OVERRIDE" : "ROLE_OVERRIDE";
        return withSources(effective, sourceType, resolvedChain);
    }

    private TeamEffectivePermission withSources(
            TeamEffectivePermission permission,
            String sourceType,
            List<TeamEffectivePermission.PermissionSource> sourceChain) {
        return new TeamEffectivePermission(
                permission.canRead(),
                permission.canWrite(),
                permission.canShare(),
                permission.canDelete(),
                permission.canManage(),
                sourceType,
                sourceChain);
    }

    private TeamEffectivePermission.PermissionSource baseSource(String role, TeamEffectivePermission permission) {
        return new TeamEffectivePermission.PermissionSource(
                "BASE_ROLE",
                null,
                null,
                SUBJECT_ROLE,
                null,
                role,
                false,
                permission.canRead(),
                permission.canWrite(),
                permission.canShare(),
                permission.canDelete(),
                permission.canManage(),
                "permission.baseRole",
                Map.of("role", role));
    }

    private TeamEffectivePermission.PermissionSource overrideSource(
            TeamFilePermissionEntity permission,
            FileEntity sourceFile,
            boolean inherited) {
        String sourceType = SUBJECT_USER.equals(permission.getSubjectType()) ? "USER_OVERRIDE" : "ROLE_OVERRIDE";
        String descriptionKey = inherited ? "permission.inheritedOverride" : "permission.currentOverride";
        return new TeamEffectivePermission.PermissionSource(
                sourceType,
                sourceFile.getId(),
                sourceFile.getFileName(),
                permission.getSubjectType(),
                permission.getSubjectUserId(),
                permission.getSubjectRole(),
                inherited,
                Boolean.TRUE.equals(permission.getCanRead()),
                Boolean.TRUE.equals(permission.getCanWrite()),
                Boolean.TRUE.equals(permission.getCanShare()),
                Boolean.TRUE.equals(permission.getCanDelete()),
                Boolean.TRUE.equals(permission.getCanManage()),
                descriptionKey,
                Map.of("fileName", sourceFile.getFileName(), "subjectType", permission.getSubjectType()));
    }

    private FileEntity requireTeamFileAnyState(Long teamId, Long fileId) {
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (file == null || !Objects.equals(file.getTeamId(), teamId)) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND);
        }
        return file;
    }

    private boolean hasActiveAncestors(Long teamId, FileEntity file) {
        Long parentId = file.getParentId();
        Set<Long> seen = new HashSet<>();
        while (parentId != null && seen.add(parentId)) {
            FileEntity parent = fileRepository.findById(parentId).orElse(null);
            if (parent == null || Boolean.TRUE.equals(parent.getIsDeleted()) || !Objects.equals(parent.getTeamId(), teamId)) {
                return false;
            }
            parentId = parent.getParentId();
        }
        return parentId == null;
    }

    private String normalizeSubjectType(String subjectType) {
        String normalized = StringUtils.hasText(subjectType) ? subjectType.trim().toUpperCase() : "";
        if (!SUBJECT_USER.equals(normalized) && !SUBJECT_ROLE.equals(normalized)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "subjectType must be USER or ROLE");
        }
        return normalized;
    }

    private String normalizeRole(String role) {
        String normalized = StringUtils.hasText(role) ? role.trim().toUpperCase() : "";
        if (!Set.of(TeamService.ROLE_ADMIN, TeamService.ROLE_EDITOR, TeamService.ROLE_VIEWER).contains(normalized)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "subjectRole must be ADMIN, EDITOR, or VIEWER");
        }
        return normalized;
    }

    private void notifyPermissionChange(
            Long teamId,
            Long actorUserId,
            FileEntity file,
            TeamFilePermissionEntity permission,
            String title) {
        String content = "Team permissions changed for file " + file.getFileName();
        String link = "/teams/" + teamId + "/files" + (file.getParentId() != null ? "/" + file.getParentId() : "");
        if (SUBJECT_USER.equals(permission.getSubjectType())) {
            teamNotificationDispatchService.enqueueUserNotification(permission.getSubjectUserId(), teamId, "TEAM_PERMISSION_UPDATE", title, content, link, true);
            return;
        }
        teamMemberRepository.findByTeamIdAndStatusOrderByCreatedAtAsc(teamId, 1).stream()
                .filter(member -> permission.getSubjectRole().equals(member.getRole()))
                .filter(member -> actorUserId == null || !actorUserId.equals(member.getUserId()))
                .forEach(member ->
                teamNotificationDispatchService.enqueueUserNotification(member.getUserId(), teamId, "TEAM_PERMISSION_UPDATE", title, content, link, true));
    }
}
