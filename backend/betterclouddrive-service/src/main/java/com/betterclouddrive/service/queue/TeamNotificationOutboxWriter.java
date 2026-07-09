package com.betterclouddrive.service.queue;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TeamNotificationOutboxWriter {

    public static final String AGGREGATE_NOTIFICATION = "NOTIFICATION";
    public static final String TARGET_USER = "NOTIFICATION_USER";
    public static final String TARGET_TEAM_MEMBERS = "NOTIFICATION_TEAM_MEMBERS";

    private final TeamEventOutboxRepository teamEventOutboxRepository;
    private final ObjectMapper objectMapper;

    public void enqueueUser(Long userId, Long teamId, String type, String title, String content, String link, boolean email) {
        if (userId == null || !StringUtils.hasText(type) || !StringUtils.hasText(title)) {
            return;
        }
        save(teamId, type, TARGET_USER, userId, new NotificationDispatchPayload(
                userId,
                teamId,
                null,
                normalize(type),
                title,
                content,
                link,
                email));
    }

    public void enqueueTeamMembers(Long teamId, Long excludeUserId, String type, String title, String content, String link, boolean email) {
        if (teamId == null || !StringUtils.hasText(type) || !StringUtils.hasText(title)) {
            return;
        }
        save(teamId, type, TARGET_TEAM_MEMBERS, teamId, new NotificationDispatchPayload(
                null,
                teamId,
                excludeUserId,
                normalize(type),
                title,
                content,
                link,
                email));
    }

    private void save(Long teamId, String type, String targetType, Long targetId, NotificationDispatchPayload payload) {
        LocalDateTime now = LocalDateTime.now();
        teamEventOutboxRepository.save(TeamEventOutboxEntity.builder()
                .aggregateType(AGGREGATE_NOTIFICATION)
                .aggregateId(teamId)
                .eventType(normalize(type))
                .actorUserId(payload.excludeUserId())
                .targetType(targetType)
                .targetId(targetId)
                .payload(toJson(payload))
                .status(TeamEventOutboxEntity.STATUS_PENDING)
                .retryCount(0)
                .availableAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private String toJson(NotificationDispatchPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification dispatch payload", e);
        }
    }

    private String normalize(String value) {
        return value.trim().toUpperCase();
    }
}
