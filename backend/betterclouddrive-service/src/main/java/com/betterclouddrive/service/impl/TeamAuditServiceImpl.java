package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import com.betterclouddrive.dal.repository.TeamMemberRepository;
import com.betterclouddrive.dal.repository.TeamOperationLogRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamEventOutboxService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TeamAuditServiceImpl implements TeamAuditService {

    private static final int EXPORT_LIMIT_MAX = 10000;

    private final TeamOperationLogRepository teamOperationLogRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamRepository teamRepository;
    private final TeamEventOutboxService teamEventOutboxService;

    @Override
    @Transactional
    public void record(Long teamId, Long actorUserId, String action, String targetType, Long targetId, String details) {
        if (teamId == null || !StringUtils.hasText(action)) {
            return;
        }
        String normalizedAction = action.trim().toUpperCase();
        String normalizedTargetType = StringUtils.hasText(targetType) ? targetType.trim().toUpperCase() : null;
        teamOperationLogRepository.save(TeamOperationLogEntity.builder()
                .teamId(teamId)
                .actorUserId(actorUserId)
                .action(normalizedAction)
                .targetType(normalizedTargetType)
                .targetId(targetId)
                .result(1)
                .statusCode(200)
                .details(details)
                .createdAt(LocalDateTime.now())
                .build());
        teamEventOutboxService.recordTeamEvent(teamId, actorUserId, normalizedAction, normalizedTargetType, targetId, details);
    }

    @Override
    public PageResult<TeamOperationLogEntity> listLogs(
            Long requesterUserId,
            Long teamId,
            Long actorUserId,
            String action,
            String targetType,
            Integer result,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int page,
            int size) {
        requireManageMembers(requesterUserId, teamId);
        Specification<TeamOperationLogEntity> spec = auditSpec(teamId, actorUserId, action, targetType, result, startAt, endAt);
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<TeamOperationLogEntity> logs = teamOperationLogRepository.findAll(spec, pageable);
        return PageResult.of(logs.getContent(), logs.getTotalElements(), page, size);
    }

    @Override
    public PageResult<TeamOperationLogEntity> exportLogs(
            Long requesterUserId,
            Long teamId,
            Long actorUserId,
            String action,
            String targetType,
            Integer result,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int limit) {
        requireManageMembers(requesterUserId, teamId);
        int safeLimit = Math.min(Math.max(limit, 1), EXPORT_LIMIT_MAX);
        Specification<TeamOperationLogEntity> spec = auditSpec(teamId, actorUserId, action, targetType, result, startAt, endAt);
        long total = teamOperationLogRepository.count(spec);
        if (total > safeLimit) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Audit export exceeds " + safeLimit + " rows. Please narrow filters.");
        }
        PageRequest pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<TeamOperationLogEntity> logs = teamOperationLogRepository.findAll(spec, pageable);
        return PageResult.of(logs.getContent(), total, 1, safeLimit);
    }

    private Specification<TeamOperationLogEntity> auditSpec(
            Long teamId,
            Long actorUserId,
            String action,
            String targetType,
            Integer result,
            LocalDateTime startAt,
            LocalDateTime endAt) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("teamId"), teamId));
            if (actorUserId != null) {
                predicates.add(cb.equal(root.get("actorUserId"), actorUserId));
            }
            if (StringUtils.hasText(action)) {
                predicates.add(cb.equal(root.get("action"), action.trim().toUpperCase()));
            }
            if (StringUtils.hasText(targetType)) {
                predicates.add(cb.equal(root.get("targetType"), targetType.trim().toUpperCase()));
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
    }

    private void requireManageMembers(Long userId, Long teamId) {
        teamRepository.findByIdAndDeletedAtIsNull(teamId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Team not found"));
        TeamMemberEntity member = teamMemberRepository.findByTeamIdAndUserIdAndStatus(teamId, userId, 1)
                .orElseThrow(() -> new BusinessException(ApiCode.FORBIDDEN, "Not a team member"));
        if (!List.of("OWNER", "ADMIN").contains(member.getRole())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Team role cannot view audit logs");
        }
    }
}
