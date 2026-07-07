package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.TeamMemberRepository;
import com.betterclouddrive.dal.repository.TeamOperationLogRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.AdminService;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamPurgeService;
import com.betterclouddrive.service.dto.AdminTeamRecoveryDetails;
import com.betterclouddrive.service.dto.TeamMemberView;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamOperationLogRepository teamOperationLogRepository;
    private final FileRepository fileRepository;
    private final TeamAuditService teamAuditService;
    private final TeamPurgeService teamPurgeService;
    private final NotificationService notificationService;

    @Override
    public PageResult<UserEntity> listUsers(String keyword, Integer status, int page, int size) {
        Specification<UserEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword + "%";
                predicates.add(cb.or(
                        cb.like(root.get("username"), pattern),
                        cb.like(root.get("email"), pattern),
                        cb.like(root.get("nickname"), pattern)
                ));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UserEntity> result = userRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    @Transactional
    public void updateUserStatus(Long userId, Integer status) {
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.NOT_FOUND, "User not found");
        }
        user.setStatus(status);
        userRepository.save(user);
        log.info("Admin updated user {} status to {}", userId, status);
    }

    @Override
    @Transactional
    public void updateUserQuota(Long userId, Long storageQuota) {
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.NOT_FOUND, "User not found");
        }
        user.setStorageQuota(storageQuota);
        userRepository.save(user);
        log.info("Admin updated user {} quota to {}", userId, storageQuota);
    }

    @Override
    public PageResult<TeamEntity> listTeams(String keyword, String status, int page, int size) {
        Specification<TeamEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            String normalizedStatus = status == null ? "active" : status.trim().toLowerCase();
            switch (normalizedStatus) {
                case "all" -> {
                }
                case "deleted" -> predicates.add(cb.isNotNull(root.get("deletedAt")));
                case "archived" -> {
                    predicates.add(cb.isNull(root.get("deletedAt")));
                    predicates.add(cb.isNotNull(root.get("archivedAt")));
                }
                default -> {
                    predicates.add(cb.isNull(root.get("deletedAt")));
                    predicates.add(cb.isNull(root.get("archivedAt")));
                }
            }
            if (keyword != null && !keyword.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + keyword.trim().toLowerCase() + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<TeamEntity> result = teamRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    public TeamEntity getTeam(Long teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Team not found"));
    }

    @Override
    public AdminTeamRecoveryDetails getTeamRecoveryDetails(Long teamId) {
        TeamEntity team = getTeam(teamId);
        Long activeFileCount = fileRepository.countByTeamIdAndIsDeletedFalse(teamId);
        Long deletedFileCount = fileRepository.countByTeamIdAndIsDeletedTrue(teamId);
        Long memberCount = teamMemberRepository.countByTeamIdAndStatus(teamId, 1);
        List<TeamOperationLogEntity> recentAuditLogs = teamOperationLogRepository.findTop5ByTeamIdOrderByCreatedAtDesc(teamId);
        TeamOperationLogEntity deleteLog = teamOperationLogRepository
                .findFirstByTeamIdAndActionOrderByCreatedAtDesc(teamId, "TEAM_DELETE")
                .orElse(null);
        String deleteReason = team.getDeletedAt() == null ? null : deleteReason(deleteLog);
        return new AdminTeamRecoveryDetails(
                team.getId(),
                team.getName(),
                team.getDeletedAt(),
                team.getDeletedBy(),
                usernameOf(team.getDeletedBy()),
                deleteReason,
                team.getPurgeAfter(),
                team.getArchivedAt(),
                team.getArchivedBy(),
                usernameOf(team.getArchivedBy()),
                activeFileCount == null ? 0 : activeFileCount,
                deletedFileCount == null ? 0 : deletedFileCount,
                memberCount == null ? 0 : memberCount,
                team.getDeletedAt() != null,
                team.getDeletedAt() != null,
                recentAuditLogs);
    }

    @Override
    @Transactional
    public TeamEntity updateTeamQuota(Long adminUserId, Long teamId, Long storageQuota) {
        TeamEntity team = getTeam(teamId);
        if (team.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Deleted team cannot be updated");
        }
        team.setStorageQuota(storageQuota);
        TeamEntity saved = teamRepository.save(team);
        teamAuditService.record(teamId, adminUserId, "QUOTA_UPDATE", "TEAM", teamId, String.valueOf(storageQuota));
        notificationService.checkTeamQuota(teamId);
        log.info("Admin {} updated team {} quota to {}", adminUserId, teamId, storageQuota);
        return saved;
    }

    @Override
    @Transactional
    public TeamEntity archiveTeam(Long adminUserId, Long teamId) {
        TeamEntity team = getTeam(teamId);
        if (team.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Deleted team cannot be archived");
        }
        if (team.getArchivedAt() == null) {
            team.setArchivedAt(LocalDateTime.now());
            team.setArchivedBy(adminUserId);
            team = teamRepository.save(team);
            teamAuditService.record(teamId, adminUserId, "TEAM_ARCHIVE", "TEAM", teamId, team.getName());
            notificationService.notifyTeamMembers(teamId, adminUserId, "TEAM_ARCHIVE", "团队已归档",
                    "团队「" + team.getName() + "」已被管理员归档。", "/admin/teams", true);
        }
        return team;
    }

    @Override
    @Transactional
    public TeamEntity unarchiveTeam(Long adminUserId, Long teamId) {
        TeamEntity team = getTeam(teamId);
        if (team.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Deleted team cannot be unarchived");
        }
        if (team.getArchivedAt() != null) {
            team.setArchivedAt(null);
            team.setArchivedBy(null);
            team = teamRepository.save(team);
            teamAuditService.record(teamId, adminUserId, "TEAM_UNARCHIVE", "TEAM", teamId, team.getName());
            notificationService.notifyTeamMembers(teamId, adminUserId, "TEAM_UNARCHIVE", "团队已取消归档",
                    "团队「" + team.getName() + "」已恢复为活跃状态。", "/teams/" + teamId + "/files", true);
        }
        return team;
    }

    @Override
    @Transactional
    public TeamEntity restoreTeam(Long adminUserId, Long teamId) {
        TeamEntity team = getTeam(teamId);
        if (team.getDeletedAt() == null) {
            return team;
        }
        team.setDeletedAt(null);
        team.setDeletedBy(null);
        team.setPurgeAfter(null);
        team.setArchivedAt(null);
        team.setArchivedBy(null);
        team = teamRepository.save(team);
        teamAuditService.record(teamId, adminUserId, "TEAM_RESTORE", "TEAM", teamId, team.getName());
        notificationService.notifyTeamMembers(teamId, adminUserId, "TEAM_RESTORE", "团队已恢复",
                "团队「" + team.getName() + "」已由管理员恢复。", "/teams/" + teamId + "/files", true);
        return team;
    }

    @Override
    @Transactional
    public void purgeTeam(Long adminUserId, Long teamId) {
        TeamEntity team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            teamPurgeService.purgeTeam(teamId);
            return;
        }
        if (team.getDeletedAt() == null) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Only deleted teams can be purged");
        }
        teamAuditService.record(teamId, adminUserId, "TEAM_PURGE", "TEAM", teamId, team.getName());
        teamPurgeService.purgeTeam(teamId);
    }

    @Override
    public List<TeamMemberView> listTeamMembers(Long teamId) {
        getTeam(teamId);
        List<TeamMemberEntity> members = teamMemberRepository.findByTeamIdAndStatusOrderByCreatedAtAsc(teamId, 1);
        Map<Long, UserEntity> usersById = new HashMap<>();
        userRepository.findAllById(members.stream().map(TeamMemberEntity::getUserId).toList())
                .forEach(user -> usersById.put(user.getId(), user));
        return members.stream()
                .map(member -> new TeamMemberView(member, usersById.get(member.getUserId())))
                .toList();
    }

    private String usernameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(UserEntity::getUsername)
                .orElse(null);
    }

    private String deleteReason(TeamOperationLogEntity deleteLog) {
        if (deleteLog == null) {
            return "团队所有者删除";
        }
        return deleteLog.getDetails() == null || deleteLog.getDetails().isBlank()
                ? "团队所有者删除"
                : "团队所有者删除：" + deleteLog.getDetails();
    }

    @Override
    public PageResult<FileEntity> listTeamFiles(
            Long teamId,
            Long parentId,
            Boolean deleted,
            String keyword,
            int page,
            int size,
            String sortBy,
            String order) {
        getTeam(teamId);
        Specification<FileEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("teamId"), teamId));
            if (parentId != null) {
                predicates.add(cb.equal(root.get("parentId"), parentId));
            } else {
                predicates.add(cb.isNull(root.get("parentId")));
            }
            if (deleted != null) {
                predicates.add(cb.equal(root.get("isDeleted"), deleted));
            }
            if (keyword != null && !keyword.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("fileName")), "%" + keyword.trim().toLowerCase() + "%"));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Sort.Direction direction = "desc".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        String safeSortBy = switch (sortBy) {
            case "createdAt", "updatedAt", "fileSize", "fileType" -> sortBy;
            default -> "fileName";
        };
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(direction, safeSortBy));
        Page<FileEntity> result = fileRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    public PageResult<TeamOperationLogEntity> listTeamAuditLogs(
            Long teamId,
            Long actorUserId,
            String action,
            String targetType,
            Integer result,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int page,
            int size) {
        getTeam(teamId);
        Specification<TeamOperationLogEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("teamId"), teamId));
            if (actorUserId != null) {
                predicates.add(cb.equal(root.get("actorUserId"), actorUserId));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action.trim()));
            }
            if (targetType != null && !targetType.isBlank()) {
                predicates.add(cb.equal(root.get("targetType"), targetType.trim()));
            }
            if (result != null) {
                predicates.add(cb.equal(root.get("result"), result));
            }
            if (startAt != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startAt));
            }
            if (endAt != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endAt));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<TeamOperationLogEntity> logs = teamOperationLogRepository.findAll(spec, pageable);
        return PageResult.of(logs.getContent(), logs.getTotalElements(), page, size);
    }

    @Override
    public PageResult<TeamOperationLogEntity> exportTeamAuditLogs(
            Long teamId,
            Long actorUserId,
            String action,
            String targetType,
            Integer result,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int limit) {
        PageResult<TeamOperationLogEntity> firstPage = listTeamAuditLogs(
                teamId, actorUserId, action, targetType, result, startAt, endAt, 1, Math.min(Math.max(limit, 1), 10000));
        if (firstPage.getTotal() > firstPage.getSize()) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Audit export exceeds " + firstPage.getSize() + " rows. Please narrow filters.");
        }
        return firstPage;
    }

    @Override
    public Map<String, Object> getSystemStats() {
        Specification<UserEntity> allSpec = (root, q, cb) -> cb.isNull(root.get("deletedAt"));
        Specification<UserEntity> activeSpec = (root, q, cb) -> cb.and(
                cb.isNull(root.get("deletedAt")),
                cb.equal(root.get("status"), 1)
        );

        long totalUsers = userRepository.count(allSpec);
        long activeUsers = userRepository.count(activeSpec);
        long totalStorageUsed = userRepository.selectTotalStorageUsed();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalUsers", totalUsers);
        stats.put("activeUsers", activeUsers);
        stats.put("totalStorageUsed", totalStorageUsed);
        return stats;
    }
}
