package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import com.betterclouddrive.dal.repository.TeamOperationLogRepository;
import com.betterclouddrive.service.TeamActivityService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.TeamService;
import com.betterclouddrive.service.dto.TeamActivityItem;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TeamActivityServiceImpl implements TeamActivityService {

    private static final int SCAN_PAGE_SIZE = 100;
    private static final Pattern SHARE_FILE_PATTERN = Pattern.compile("\\bfile\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);

    private final TeamOperationLogRepository teamOperationLogRepository;
    private final TeamService teamService;
    private final TeamPermissionService teamPermissionService;

    @Override
    public PageResult<TeamActivityItem> listActivities(Long userId, Long teamId, int page, int size) {
        teamService.requireRead(userId, teamId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<TeamActivityItem> visible = loadVisibleActivities(userId, teamId);
        int fromIndex = Math.min((safePage - 1) * safeSize, visible.size());
        int toIndex = Math.min(fromIndex + safeSize, visible.size());
        return PageResult.of(visible.subList(fromIndex, toIndex), visible.size(), safePage, safeSize);
    }

    private List<TeamActivityItem> loadVisibleActivities(Long userId, Long teamId) {
        Specification<TeamOperationLogEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("teamId"), teamId));
            predicates.add(cb.equal(root.get("result"), 1));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        List<TeamActivityItem> visible = new ArrayList<>();
        Map<Long, Boolean> readableFiles = new HashMap<>();
        int scanPage = 0;
        Page<TeamOperationLogEntity> logs;
        do {
            logs = teamOperationLogRepository.findAll(spec, PageRequest.of(scanPage, SCAN_PAGE_SIZE, sort));
            for (TeamOperationLogEntity log : logs.getContent()) {
                if (isVisibleToUser(userId, log, readableFiles)) {
                    visible.add(toActivity(log));
                }
            }
            scanPage++;
        } while (logs.hasNext());
        return visible;
    }

    private boolean isVisibleToUser(Long userId, TeamOperationLogEntity log, Map<Long, Boolean> readableFiles) {
        Long fileId = scopedFileId(log);
        if (fileId == null) {
            return true;
        }
        if (readableFiles.containsKey(fileId)) {
            return readableFiles.get(fileId);
        }
        try {
            boolean readable = teamPermissionService.effectivePermissions(userId, log.getTeamId(), fileId).canRead();
            readableFiles.put(fileId, readable);
            return readable;
        } catch (BusinessException e) {
            readableFiles.put(fileId, false);
            return false;
        }
    }

    private Long scopedFileId(TeamOperationLogEntity log) {
        String targetType = normalize(log.getTargetType());
        if ("FILE".equals(targetType)) {
            return log.getTargetId();
        }
        if ("SHARE".equals(targetType)) {
            return fileIdFromDetails(log.getDetails());
        }
        return null;
    }

    private Long fileIdFromDetails(String details) {
        if (!StringUtils.hasText(details)) {
            return null;
        }
        Matcher matcher = SHARE_FILE_PATTERN.matcher(details);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private TeamActivityItem toActivity(TeamOperationLogEntity log) {
        return new TeamActivityItem(
                log.getId(),
                log.getTeamId(),
                log.getActorUserId(),
                log.getAction(),
                log.getTargetType(),
                log.getTargetId(),
                summary(log),
                log.getCreatedAt());
    }

    private String summary(TeamOperationLogEntity log) {
        String action = normalize(log.getAction());
        if (action.startsWith("INVITE_")) {
            return "团队邀请状态已更新";
        }
        if (StringUtils.hasText(log.getDetails())) {
            return log.getDetails().trim();
        }
        String target = StringUtils.hasText(log.getTargetType())
                ? " " + log.getTargetType() + (log.getTargetId() != null ? " " + log.getTargetId() : "")
                : "";
        return action + target;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}
