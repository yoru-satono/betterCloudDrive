package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.NotificationEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class NotificationResponse {
    private Long id;
    private Long userId;
    private Long teamId;
    private String type;
    private String titleKey;
    private String contentKey;
    private Map<String, Object> messageParams;
    private String link;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
    @JsonProperty("isRead")
    private boolean isRead;

    public static NotificationResponse from(NotificationEntity entity) {
        return NotificationResponse.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .teamId(entity.getTeamId())
                .type(entity.getType())
                .titleKey(entity.getTitleKey())
                .contentKey(entity.getContentKey())
                .messageParams(entity.getMessageParams() == null ? Map.of() : entity.getMessageParams())
                .link(entity.getLink())
                .readAt(entity.getReadAt())
                .createdAt(entity.getCreatedAt())
                .isRead(entity.getIsRead())
                .build();
    }
}
