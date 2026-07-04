package com.betterclouddrive.service.impl;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.betterclouddrive.service.TeamEventOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TeamEventOutboxServiceImpl implements TeamEventOutboxService {

    private static final String AGGREGATE_TEAM = "TEAM";
    private static final String AGGREGATE_NOTIFICATION = "NOTIFICATION";

    private final TeamEventOutboxRepository teamEventOutboxRepository;

    @Override
    @Transactional
    public void recordTeamEvent(Long teamId, Long actorUserId, String eventType, String targetType, Long targetId, String payload) {
        record(AGGREGATE_TEAM, teamId, actorUserId, eventType, targetType, targetId, payload);
    }

    @Override
    @Transactional
    public void recordNotificationEvent(Long notificationId, Long userId, Long teamId, String eventType, String payload) {
        String notificationPayload = "userId=" + userId
                + (teamId != null ? ",teamId=" + teamId : "")
                + (StringUtils.hasText(payload) ? ",content=" + payload : "");
        record(AGGREGATE_NOTIFICATION, notificationId, userId, eventType, "NOTIFICATION", notificationId, notificationPayload);
    }

    private void record(
            String aggregateType,
            Long aggregateId,
            Long actorUserId,
            String eventType,
            String targetType,
            Long targetId,
            String payload) {
        if (!StringUtils.hasText(aggregateType) || !StringUtils.hasText(eventType)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        teamEventOutboxRepository.save(TeamEventOutboxEntity.builder()
                .aggregateType(normalize(aggregateType))
                .aggregateId(aggregateId)
                .eventType(normalize(eventType))
                .actorUserId(actorUserId)
                .targetType(StringUtils.hasText(targetType) ? normalize(targetType) : null)
                .targetId(targetId)
                .payload(payload)
                .status(TeamEventOutboxEntity.STATUS_PENDING)
                .retryCount(0)
                .availableAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private String normalize(String value) {
        return value.trim().toUpperCase();
    }
}
