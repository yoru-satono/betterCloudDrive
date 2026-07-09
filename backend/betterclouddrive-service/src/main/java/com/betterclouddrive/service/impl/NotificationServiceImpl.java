package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.NotificationEntity;
import com.betterclouddrive.dal.entity.NotificationPreferenceEntity;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.NotificationPreferenceRepository;
import com.betterclouddrive.dal.repository.NotificationRepository;
import com.betterclouddrive.dal.repository.TeamMemberRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.EmailService;
import com.betterclouddrive.service.NotificationRealtimePublisher;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.TeamEventOutboxService;
import com.betterclouddrive.service.TeamService;
import com.betterclouddrive.service.config.QueueProperties;
import com.betterclouddrive.service.queue.TeamNotificationOutboxWriter;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final int ACTIVE = 1;
    private static final String QUOTA_ALERT_PREFIX = "team:quota:alert:";
    private static final String TEAM_STORAGE_INCR_PREFIX = "team:storage:incr:";
    private static final List<String> PREFERENCE_TYPES = List.of(
            "TEAM_INVITE",
            "TEAM_INVITE_ACCEPT",
            "TEAM_INVITE_REJECT",
            "TEAM_QUOTA_ALERT",
            "TEAM_ARCHIVE",
            "TEAM_UNARCHIVE",
            "TEAM_RESTORE",
            "TEAM_PERMISSION_UPDATE",
            "TEAM_DELETE",
            "TEAM_MEMBER_ADD",
            "TEAM_ROLE_UPDATE",
            "TEAM_MEMBER_REMOVE",
            "TEAM_OWNER_TRANSFER",
            "TEAM_OFFICE_SAVE_FAILED"
    );

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final StringRedisTemplate redisTemplate;
    private final TeamEventOutboxService teamEventOutboxService;
    private final NotificationRealtimePublisher notificationRealtimePublisher;
    private final TeamNotificationOutboxWriter teamNotificationOutboxWriter;
    private final QueueProperties queueProperties;

    @Override
    public PageResult<NotificationEntity> listNotifications(Long userId, Boolean unreadOnly, String type, int page, int size) {
        Specification<NotificationEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (Boolean.TRUE.equals(unreadOnly)) {
                predicates.add(cb.isNull(root.get("readAt")));
            }
            if (StringUtils.hasText(type)) {
                predicates.add(cb.equal(root.get("type"), type.trim().toUpperCase()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<NotificationEntity> result = notificationRepository.findAll(spec, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Override
    public long countUnread(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    @Override
    public List<String> supportedPreferenceTypes() {
        return PREFERENCE_TYPES;
    }

    @Override
    public Map<String, Boolean> getPreferences(Long userId) {
        Map<String, Boolean> result = defaultPreferences();
        notificationPreferenceRepository.findByUserId(userId).forEach(preference -> {
            String type = normalizePreferenceType(preference.getNotificationType());
            if (PREFERENCE_TYPES.contains(type)) {
                result.put(type, Boolean.TRUE.equals(preference.getEnabled()));
            }
        });
        return result;
    }

    @Override
    @Transactional
    public Map<String, Boolean> updatePreferences(Long userId, Map<String, Boolean> preferences) {
        if (preferences == null) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Notification preferences are required");
        }
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Boolean> entry : preferences.entrySet()) {
            String type = normalizePreferenceType(entry.getKey());
            if (!PREFERENCE_TYPES.contains(type) || entry.getValue() == null) {
                throw new BusinessException(ApiCode.BAD_REQUEST, "Unsupported notification preference type");
            }
            NotificationPreferenceEntity preference = notificationPreferenceRepository
                    .findByUserIdAndNotificationType(userId, type)
                    .orElseGet(() -> NotificationPreferenceEntity.builder()
                            .userId(userId)
                            .notificationType(type)
                            .createdAt(now)
                            .build());
            preference.setEnabled(entry.getValue());
            preference.setUpdatedAt(now);
            notificationPreferenceRepository.save(preference);
        }
        return getPreferences(userId);
    }

    @Override
    @Transactional
    public void markRead(Long userId, Long notificationId) {
        NotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND, "Notification not found"));
        if (!userId.equals(notification.getUserId())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Cannot read another user's notification");
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now());
            notificationRepository.save(notification);
            publishNotificationsChangedAfterCommit(userId);
        }
    }

    @Override
    @Transactional
    public void markAllRead(Long userId) {
        int updated = notificationRepository.markAllReadByUserId(userId, LocalDateTime.now());
        if (updated > 0) {
            publishNotificationsChangedAfterCommit(userId);
        }
    }

    @Override
    @Transactional
    public void notifyUser(Long userId, Long teamId, String type, String title, String content, String link, boolean email) {
        if (userId == null || !StringUtils.hasText(type) || !StringUtils.hasText(title)) {
            return;
        }
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            return;
        }
        String normalizedType = type.trim().toUpperCase();
        if (!isNotificationEnabled(userId, normalizedType)) {
            log.debug("Notification type {} is disabled for user {}", normalizedType, userId);
            return;
        }
        NotificationEntity notification = notificationRepository.save(NotificationEntity.builder()
                .userId(userId)
                .teamId(teamId)
                .type(normalizedType)
                .title(title.trim())
                .content(content)
                .link(link)
                .createdAt(LocalDateTime.now())
                .build());
        teamEventOutboxService.recordNotificationEvent(notification.getId(), userId, teamId, normalizedType, content);
        publishNotificationsChangedAfterCommit(userId);
        if (email) {
            try {
                emailService.sendNotification(user.getEmail(), title, content, link);
            } catch (Exception e) {
                log.warn("Failed to enqueue notification email to user {}", userId, e);
            }
        }
    }

    @Override
    public void notifyTeamMembers(Long teamId, Long actorUserId, String type, String title, String content, String link, boolean email) {
        teamMemberRepository.findByTeamIdAndStatusOrderByCreatedAtAsc(teamId, ACTIVE).stream()
                .map(TeamMemberEntity::getUserId)
                .filter(userId -> actorUserId == null || !actorUserId.equals(userId))
                .forEach(userId -> notifyUser(userId, teamId, type, title, content, link, email));
    }

    @Override
    public void checkTeamQuota(Long teamId) {
        TeamEntity team = teamRepository.findByIdAndDeletedAtIsNull(teamId).orElse(null);
        if (team == null || team.getStorageQuota() == null || team.getStorageQuota() <= 0) {
            return;
        }
        long used = team.getStorageUsed() != null ? team.getStorageUsed() : 0L;
        String pending = redisTemplate.opsForValue().get(TEAM_STORAGE_INCR_PREFIX + teamId);
        if (pending != null) {
            try {
                used += Long.parseLong(pending);
            } catch (NumberFormatException ignored) {
            }
        }
        int ratio = (int) Math.floor(used * 100.0 / team.getStorageQuota());
        int threshold = ratio >= 100 ? 100 : ratio >= 90 ? 90 : ratio >= 80 ? 80 : 0;
        resetQuotaThresholdsBelow(team.getId(), threshold);
        if (threshold == 0) {
            return;
        }
        notifyQuotaThreshold(team, threshold);
    }

    @Override
    public void notifyTeamQuotaExceeded(Long teamId) {
        TeamEntity team = teamRepository.findByIdAndDeletedAtIsNull(teamId).orElse(null);
        if (team != null) {
            notifyQuotaThreshold(team, 100);
        }
    }

    private void notifyQuotaThreshold(TeamEntity team, int threshold) {
        String key = QUOTA_ALERT_PREFIX + team.getId() + ":" + threshold;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1");
        if (!Boolean.TRUE.equals(first)) {
            return;
        }
        String title = "团队配额告警";
        String content = "团队「" + team.getName() + "」存储用量已达到 " + threshold + "%。";
        String link = "/teams/" + team.getId() + "/settings";
        teamMemberRepository.findByTeamIdAndStatusOrderByCreatedAtAsc(team.getId(), ACTIVE).stream()
                .filter(member -> Set.of(TeamService.ROLE_OWNER, TeamService.ROLE_ADMIN).contains(member.getRole()))
                .map(TeamMemberEntity::getUserId)
                .forEach(userId -> {
                    if (queueProperties.isEnabled()) {
                        teamNotificationOutboxWriter.enqueueUser(userId, team.getId(), "TEAM_QUOTA_ALERT", title, content, link, true);
                    } else {
                        notifyUser(userId, team.getId(), "TEAM_QUOTA_ALERT", title, content, link, true);
                    }
                });
    }

    private void resetQuotaThresholdsBelow(Long teamId, int currentThreshold) {
        for (int threshold : List.of(80, 90, 100)) {
            if (currentThreshold < threshold) {
                redisTemplate.delete(QUOTA_ALERT_PREFIX + teamId + ":" + threshold);
            }
        }
    }

    private void publishNotificationsChangedAfterCommit(Long userId) {
        if (userId == null) {
            return;
        }
        Runnable publish = () -> notificationRealtimePublisher.publishNotificationsChanged(userId, countUnread(userId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private Map<String, Boolean> defaultPreferences() {
        return PREFERENCE_TYPES.stream()
                .collect(Collectors.toMap(
                        type -> type,
                        type -> true,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private boolean isNotificationEnabled(Long userId, String type) {
        if (!PREFERENCE_TYPES.contains(type)) {
            return true;
        }
        return notificationPreferenceRepository.findByUserIdAndNotificationType(userId, type)
                .map(NotificationPreferenceEntity::getEnabled)
                .map(Boolean.TRUE::equals)
                .orElse(true);
    }

    private String normalizePreferenceType(String type) {
        if (!StringUtils.hasText(type)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Notification preference type is required");
        }
        return type.trim().toUpperCase();
    }
}
