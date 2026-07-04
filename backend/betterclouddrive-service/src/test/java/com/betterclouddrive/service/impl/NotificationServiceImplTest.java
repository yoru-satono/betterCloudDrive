package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.dto.PageResult;
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
import com.betterclouddrive.service.TeamEventOutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationPreferenceRepository notificationPreferenceRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private TeamEventOutboxService teamEventOutboxService;
    @Mock private NotificationRealtimePublisher notificationRealtimePublisher;
    @InjectMocks private NotificationServiceImpl notificationService;

    @Test
    void listNotifications_shouldPassPagingAndTypeFilter() {
        NotificationEntity notification = NotificationEntity.builder()
                .id(1L)
                .userId(2L)
                .type("TEAM_QUOTA_ALERT")
                .build();
        when(notificationRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(notification), PageRequest.of(0, 20), 1));

        PageResult<NotificationEntity> result =
                notificationService.listNotifications(2L, true, " team_quota_alert ", 1, 20);

        assertThat(result.getRecords()).containsExactly(notification);
        verify(notificationRepository).findAll(any(Specification.class), argThat((Pageable pageable) ->
                pageable.getPageNumber() == 0
                        && pageable.getPageSize() == 20
                        && pageable.getSort().getOrderFor("createdAt") != null));
    }

    @Test
    void markAllRead_shouldUseBulkRepositoryUpdate() {
        when(notificationRepository.markAllReadByUserId(eq(2L), any())).thenReturn(3);
        when(notificationRepository.countByUserIdAndReadAtIsNull(2L)).thenReturn(0L);

        notificationService.markAllRead(2L);

        verify(notificationRepository).markAllReadByUserId(eq(2L), any());
        verify(notificationRealtimePublisher).publishNotificationsChanged(2L, 0L);
    }

    @Test
    void markRead_shouldPublishUnreadCountWhenNotificationChanges() {
        NotificationEntity notification = NotificationEntity.builder()
                .id(7L)
                .userId(2L)
                .readAt(null)
                .build();
        when(notificationRepository.findById(7L)).thenReturn(Optional.of(notification));
        when(notificationRepository.countByUserIdAndReadAtIsNull(2L)).thenReturn(4L);

        notificationService.markRead(2L, 7L);

        verify(notificationRepository).save(notification);
        verify(notificationRealtimePublisher).publishNotificationsChanged(2L, 4L);
    }

    @Test
    void getPreferences_shouldDefaultSupportedTypesToEnabledAndApplyOverrides() {
        when(notificationPreferenceRepository.findByUserId(2L)).thenReturn(List.of(
                NotificationPreferenceEntity.builder()
                        .userId(2L)
                        .notificationType("team_invite")
                        .enabled(false)
                        .build()));

        Map<String, Boolean> preferences = notificationService.getPreferences(2L);

        assertThat(preferences).containsEntry("TEAM_INVITE", false);
        assertThat(preferences).containsEntry("TEAM_QUOTA_ALERT", true);
        assertThat(preferences.keySet()).containsAll(notificationService.supportedPreferenceTypes());
    }

    @Test
    void updatePreferences_shouldUpsertNormalizedValues() {
        NotificationPreferenceEntity existing = NotificationPreferenceEntity.builder()
                .id(8L)
                .userId(2L)
                .notificationType("TEAM_INVITE")
                .enabled(true)
                .build();
        when(notificationPreferenceRepository.findByUserIdAndNotificationType(2L, "TEAM_INVITE"))
                .thenReturn(Optional.of(existing));
        when(notificationPreferenceRepository.findByUserIdAndNotificationType(2L, "TEAM_DELETE"))
                .thenReturn(Optional.empty());
        when(notificationPreferenceRepository.findByUserId(2L)).thenReturn(List.of(
                NotificationPreferenceEntity.builder().userId(2L).notificationType("TEAM_INVITE").enabled(false).build(),
                NotificationPreferenceEntity.builder().userId(2L).notificationType("TEAM_DELETE").enabled(false).build()));

        Map<String, Boolean> preferences = notificationService.updatePreferences(2L, Map.of(
                " team_invite ", false,
                "TEAM_DELETE", false));

        verify(notificationPreferenceRepository).save(argThat(preference ->
                Long.valueOf(8L).equals(preference.getId())
                        && "TEAM_INVITE".equals(preference.getNotificationType())
                        && Boolean.FALSE.equals(preference.getEnabled())));
        verify(notificationPreferenceRepository).save(argThat(preference ->
                preference.getId() == null
                        && Long.valueOf(2L).equals(preference.getUserId())
                        && "TEAM_DELETE".equals(preference.getNotificationType())
                        && Boolean.FALSE.equals(preference.getEnabled())));
        assertThat(preferences).containsEntry("TEAM_INVITE", false).containsEntry("TEAM_DELETE", false);
    }

    @Test
    void notifyUser_shouldRecordNotificationOutboxEvent() {
        NotificationEntity saved = NotificationEntity.builder()
                .id(99L)
                .userId(2L)
                .teamId(10L)
                .type("TEAM_INVITE")
                .build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(UserEntity.builder().id(2L).email("user@test.local").build()));
        when(notificationRepository.save(any())).thenReturn(saved);
        when(notificationRepository.countByUserIdAndReadAtIsNull(2L)).thenReturn(5L);
        when(notificationPreferenceRepository.findByUserIdAndNotificationType(2L, "TEAM_INVITE")).thenReturn(Optional.empty());

        notificationService.notifyUser(2L, 10L, " team_invite ", "邀请", "加入团队", "/teams/10", false);

        verify(notificationRepository).save(argThat(notification ->
                Long.valueOf(2L).equals(notification.getUserId())
                        && Long.valueOf(10L).equals(notification.getTeamId())
                        && "TEAM_INVITE".equals(notification.getType())));
        verify(teamEventOutboxService).recordNotificationEvent(99L, 2L, 10L, "TEAM_INVITE", "加入团队");
        verify(notificationRealtimePublisher).publishNotificationsChanged(2L, 5L);
    }

    @Test
    void notifyUser_shouldSkipDisabledNotificationType() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(UserEntity.builder().id(2L).email("user@test.local").build()));
        when(notificationPreferenceRepository.findByUserIdAndNotificationType(2L, "TEAM_INVITE"))
                .thenReturn(Optional.of(NotificationPreferenceEntity.builder()
                        .userId(2L)
                        .notificationType("TEAM_INVITE")
                        .enabled(false)
                        .build()));

        notificationService.notifyUser(2L, 10L, "TEAM_INVITE", "邀请", "加入团队", "/teams/10", true);

        verify(notificationRepository, never()).save(any());
        verify(teamEventOutboxService, never()).recordNotificationEvent(any(), any(), any(), any(), any());
        verify(notificationRealtimePublisher, never()).publishNotificationsChanged(anyLong(), anyLong());
        verify(emailService, never()).sendNotification(any(), any(), any(), any());
    }

    @Test
    void checkTeamQuota_shouldResetHigherThresholdsAndNotifyManagersOnce() {
        TeamEntity team = TeamEntity.builder()
                .id(10L)
                .name("Product")
                .storageUsed(850L)
                .storageQuota(1000L)
                .build();
        TeamMemberEntity owner = TeamMemberEntity.builder().teamId(10L).userId(2L).role("OWNER").status(1).build();
        TeamMemberEntity admin = TeamMemberEntity.builder().teamId(10L).userId(3L).role("ADMIN").status(1).build();
        TeamMemberEntity viewer = TeamMemberEntity.builder().teamId(10L).userId(4L).role("VIEWER").status(1).build();
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(team));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("team:storage:incr:10")).thenReturn(null);
        when(valueOperations.setIfAbsent("team:quota:alert:10:80", "1")).thenReturn(true);
        when(teamMemberRepository.findByTeamIdAndStatusOrderByCreatedAtAsc(10L, 1))
                .thenReturn(List.of(owner, admin, viewer));
        when(userRepository.findById(2L)).thenReturn(Optional.of(UserEntity.builder().id(2L).email("owner@test.local").build()));
        when(userRepository.findById(3L)).thenReturn(Optional.of(UserEntity.builder().id(3L).email("admin@test.local").build()));
        when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(notificationPreferenceRepository.findByUserIdAndNotificationType(anyLong(), eq("TEAM_QUOTA_ALERT")))
                .thenReturn(Optional.empty());

        notificationService.checkTeamQuota(10L);

        verify(redisTemplate).delete("team:quota:alert:10:90");
        verify(redisTemplate).delete("team:quota:alert:10:100");
        verify(notificationRepository, times(2)).save(argThat(notification ->
                "TEAM_QUOTA_ALERT".equals(notification.getType())
                        && Long.valueOf(10L).equals(notification.getTeamId())));
        verify(teamEventOutboxService, times(2)).recordNotificationEvent(
                isNull(), anyLong(), eq(10L), eq("TEAM_QUOTA_ALERT"), contains("存储用量已达到 80%"));
        verify(userRepository, never()).findById(4L);
    }
}
