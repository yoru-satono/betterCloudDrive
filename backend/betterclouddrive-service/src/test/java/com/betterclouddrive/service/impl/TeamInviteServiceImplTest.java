package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.TeamInviteEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.TeamInviteRepository;
import com.betterclouddrive.dal.repository.TeamMemberRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.EmailService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamNotificationDispatchService;
import com.betterclouddrive.service.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamInviteServiceImplTest {
    @Mock TeamInviteRepository invites;
    @Mock TeamMemberRepository members;
    @Mock UserRepository users;
    @Mock TeamService teams;
    @Mock EmailService email;
    @Mock TeamNotificationDispatchService notifications;
    @Mock TeamAuditService audit;
    TeamInviteServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TeamInviteServiceImpl(invites, members, users, teams, email, notifications, audit);
        setField("inviteTtlHours", 168L);
        setField("appPublicUrl", "http://localhost:3000");
    }

    @Test
    void nonOwnerCannotInviteAdministrator() {
        when(teams.requireManageMembers(1L, 2L)).thenReturn(member("ADMIN"));
        when(teams.requireTeam(2L)).thenReturn(team());

        assertThatThrownBy(() -> service.createInvite(1L, 2L, "User@Test.Local", "ADMIN"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(invites, email);
    }

    @Test
    void expiredPendingInviteIsPersistedAndExposedAsExpired() {
        var invite = invite("PENDING", "alice@test.local");
        invite.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(invites.findByToken("token")).thenReturn(Optional.of(invite));
        when(invites.save(invite)).thenReturn(invite);
        when(teams.requireTeam(2L)).thenReturn(team());
        when(users.findById(1L)).thenReturn(Optional.empty());

        var view = service.getInviteByToken(" token ");

        assertThat(view.invite().getStatus()).isEqualTo("EXPIRED");
        verify(invites).save(invite);
    }

    @Test
    void acceptingUserMustMatchInvitedEmail() {
        var invite = invite("PENDING", "invited@test.local");
        when(invites.findByToken("token")).thenReturn(Optional.of(invite));
        when(users.findById(9L)).thenReturn(Optional.of(UserEntity.builder()
                .id(9L).email("other@test.local").username("other").build()));

        assertThatThrownBy(() -> service.acceptInvite(9L, "token"))
                .isInstanceOf(BusinessException.class);
        verify(members, never()).save(any());
    }

    private static TeamMemberEntity member(String role) {
        return TeamMemberEntity.builder().teamId(2L).userId(1L).role(role).status(1).build();
    }

    private static TeamEntity team() {
        return TeamEntity.builder().id(2L).name("Test team").build();
    }

    private static TeamInviteEntity invite(String status, String email) {
        return TeamInviteEntity.builder().id(3L).teamId(2L).email(email).role("VIEWER")
                .token("token").status(status).invitedByUserId(1L)
                .expiresAt(LocalDateTime.now().plusHours(1)).createdAt(LocalDateTime.now()).build();
    }

    private void setField(String name, Object value) {
        try {
            var field = TeamInviteServiceImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
