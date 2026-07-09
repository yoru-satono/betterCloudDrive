package com.betterclouddrive.service.queue;

import com.betterclouddrive.dal.entity.TeamEventOutboxEntity;
import com.betterclouddrive.dal.repository.TeamEventOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TeamNotificationOutboxWriterTest {

    @Mock
    private TeamEventOutboxRepository teamEventOutboxRepository;

    private ObjectMapper objectMapper;
    private TeamNotificationOutboxWriter writer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        writer = new TeamNotificationOutboxWriter(teamEventOutboxRepository, objectMapper);
    }

    @Test
    void enqueueUser_shouldPersistNotificationDispatchEvent() throws Exception {
        writer.enqueueUser(2L, 10L, " team_invite ", "团队邀请", "加入团队", "/team-invites/token", false);

        ArgumentCaptor<TeamEventOutboxEntity> eventCaptor = ArgumentCaptor.forClass(TeamEventOutboxEntity.class);
        verify(teamEventOutboxRepository).save(eventCaptor.capture());
        TeamEventOutboxEntity event = eventCaptor.getValue();
        assertThat(event.getAggregateType()).isEqualTo(TeamNotificationOutboxWriter.AGGREGATE_NOTIFICATION);
        assertThat(event.getAggregateId()).isEqualTo(10L);
        assertThat(event.getEventType()).isEqualTo("TEAM_INVITE");
        assertThat(event.getTargetType()).isEqualTo(TeamNotificationOutboxWriter.TARGET_USER);
        assertThat(event.getTargetId()).isEqualTo(2L);
        assertThat(event.getStatus()).isEqualTo(TeamEventOutboxEntity.STATUS_PENDING);
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getAvailableAt()).isNotNull();
        NotificationDispatchPayload payload = objectMapper.readValue(event.getPayload(), NotificationDispatchPayload.class);
        assertThat(payload.userId()).isEqualTo(2L);
        assertThat(payload.teamId()).isEqualTo(10L);
        assertThat(payload.type()).isEqualTo("TEAM_INVITE");
        assertThat(payload.title()).isEqualTo("团队邀请");
        assertThat(payload.content()).isEqualTo("加入团队");
        assertThat(payload.link()).isEqualTo("/team-invites/token");
        assertThat(payload.email()).isFalse();
    }

    @Test
    void enqueueTeamMembers_shouldPersistTeamMembersDispatchEvent() throws Exception {
        writer.enqueueTeamMembers(10L, 1L, "team_delete", "团队已删除", "团队已被删除", null, true);

        ArgumentCaptor<TeamEventOutboxEntity> eventCaptor = ArgumentCaptor.forClass(TeamEventOutboxEntity.class);
        verify(teamEventOutboxRepository).save(eventCaptor.capture());
        TeamEventOutboxEntity event = eventCaptor.getValue();
        assertThat(event.getAggregateType()).isEqualTo(TeamNotificationOutboxWriter.AGGREGATE_NOTIFICATION);
        assertThat(event.getTargetType()).isEqualTo(TeamNotificationOutboxWriter.TARGET_TEAM_MEMBERS);
        assertThat(event.getTargetId()).isEqualTo(10L);
        assertThat(event.getActorUserId()).isEqualTo(1L);
        NotificationDispatchPayload payload = objectMapper.readValue(event.getPayload(), NotificationDispatchPayload.class);
        assertThat(payload.userId()).isNull();
        assertThat(payload.teamId()).isEqualTo(10L);
        assertThat(payload.excludeUserId()).isEqualTo(1L);
        assertThat(payload.type()).isEqualTo("TEAM_DELETE");
        assertThat(payload.email()).isTrue();
    }

    @Test
    void enqueueUser_shouldSkipInvalidPayload() {
        writer.enqueueUser(null, 10L, "TEAM_INVITE", "团队邀请", "加入团队", null, false);
        writer.enqueueUser(2L, 10L, "", "团队邀请", "加入团队", null, false);
        writer.enqueueUser(2L, 10L, "TEAM_INVITE", " ", "加入团队", null, false);

        verify(teamEventOutboxRepository, never()).save(any());
    }
}
