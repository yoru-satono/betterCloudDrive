package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.NotificationEntity;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.NotificationRepository;
import com.betterclouddrive.dal.repository.TeamMemberRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.EmailService;
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
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private TeamEventOutboxService teamEventOutboxService;
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
        notificationService.markAllRead(2L);

        verify(notificationRepository).markAllReadByUserId(eq(2L), any());
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

        notificationService.notifyUser(2L, 10L, " team_invite ", "邀请", "加入团队", "/teams/10", false);

        verify(notificationRepository).save(argThat(notification ->
                Long.valueOf(2L).equals(notification.getUserId())
                        && Long.valueOf(10L).equals(notification.getTeamId())
                        && "TEAM_INVITE".equals(notification.getType())));
        verify(teamEventOutboxService).recordNotificationEvent(99L, 2L, 10L, "TEAM_INVITE", "加入团队");
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
