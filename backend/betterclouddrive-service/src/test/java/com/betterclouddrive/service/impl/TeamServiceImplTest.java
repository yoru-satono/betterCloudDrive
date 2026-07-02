package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.TeamMemberRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamService;
import com.betterclouddrive.service.dto.TeamMemberView;
import com.betterclouddrive.service.dto.TeamView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamServiceImplTest {

    @Mock private TeamRepository teamRepository;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private TeamAuditService teamAuditService;
    @Mock private NotificationService notificationService;
    @InjectMocks private TeamServiceImpl teamService;

    @Test
    void createTeam_shouldCreateOwnerMembership() {
        when(teamRepository.save(any(TeamEntity.class))).thenAnswer(inv -> {
            TeamEntity team = inv.getArgument(0);
            team.setId(10L);
            return team;
        });
        when(teamMemberRepository.save(any(TeamMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TeamView view = teamService.createTeam(1L, "  Product  ");

        assertThat(view.team().getName()).isEqualTo("Product");
        assertThat(view.role()).isEqualTo(TeamService.ROLE_OWNER);

        ArgumentCaptor<TeamMemberEntity> memberCaptor = ArgumentCaptor.forClass(TeamMemberEntity.class);
        verify(teamMemberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getTeamId()).isEqualTo(10L);
        assertThat(memberCaptor.getValue().getUserId()).isEqualTo(1L);
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(TeamService.ROLE_OWNER);
    }

    @Test
    void requireWrite_shouldRejectViewer() {
        TeamEntity team = TeamEntity.builder().id(10L).name("Team").build();
        TeamMemberEntity member = TeamMemberEntity.builder()
                .teamId(10L).userId(2L).role(TeamService.ROLE_VIEWER).status(1).build();
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndStatus(10L, 2L, 1)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> teamService.requireWrite(2L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
    }

    @Test
    void addMember_shouldAddExistingUserByUsername() {
        TeamEntity team = TeamEntity.builder().id(10L).name("Team").build();
        TeamMemberEntity owner = TeamMemberEntity.builder()
                .teamId(10L).userId(1L).role(TeamService.ROLE_OWNER).status(1).build();
        UserEntity target = UserEntity.builder().id(2L).username("chain").email("chain@test.local").build();
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndStatus(10L, 1L, 1)).thenReturn(Optional.of(owner));
        when(userRepository.findByUsername("chain")).thenReturn(Optional.of(target));
        when(teamMemberRepository.findByTeamIdAndUserId(10L, 2L)).thenReturn(Optional.empty());
        when(teamMemberRepository.save(any(TeamMemberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TeamMemberView view = teamService.addMember(1L, 10L, "chain", TeamService.ROLE_EDITOR);

        assertThat(view.user().getUsername()).isEqualTo("chain");
        assertThat(view.member().getRole()).isEqualTo(TeamService.ROLE_EDITOR);
        assertThat(view.member().getStatus()).isEqualTo(1);
    }

    @Test
    void addMember_shouldRejectAdminAddingAdmin() {
        TeamEntity team = TeamEntity.builder().id(10L).name("Team").build();
        TeamMemberEntity admin = TeamMemberEntity.builder()
                .teamId(10L).userId(1L).role(TeamService.ROLE_ADMIN).status(1).build();
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndStatus(10L, 1L, 1)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> teamService.addMember(1L, 10L, "chain", TeamService.ROLE_ADMIN))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(userRepository, never()).findByUsername(any());
        verify(teamMemberRepository, never()).save(any());
    }

    @Test
    void updateMember_shouldRejectOwnerRoleChange() {
        TeamEntity team = TeamEntity.builder().id(10L).name("Team").build();
        TeamMemberEntity owner = TeamMemberEntity.builder()
                .teamId(10L).userId(1L).role(TeamService.ROLE_OWNER).status(1).build();
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndStatus(10L, 1L, 1)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> teamService.updateMember(1L, 10L, 1L, TeamService.ROLE_EDITOR))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(403));
        verify(teamMemberRepository, never()).save(any());
    }

    @Test
    void leaveTeam_shouldMarkNonOwnerRemoved() {
        TeamEntity team = TeamEntity.builder().id(10L).name("Team").build();
        TeamMemberEntity member = TeamMemberEntity.builder()
                .teamId(10L).userId(2L).role(TeamService.ROLE_EDITOR).status(1).build();
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndStatus(10L, 2L, 1)).thenReturn(Optional.of(member));

        teamService.leaveTeam(2L, 10L);

        assertThat(member.getStatus()).isEqualTo(0);
        verify(teamMemberRepository).save(member);
    }

    @Test
    void leaveTeam_shouldRejectOwner() {
        TeamEntity team = TeamEntity.builder().id(10L).name("Team").build();
        TeamMemberEntity owner = TeamMemberEntity.builder()
                .teamId(10L).userId(1L).role(TeamService.ROLE_OWNER).status(1).build();
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndStatus(10L, 1L, 1)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> teamService.leaveTeam(1L, 10L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteTeam_shouldSoftDeleteAndRecordAudit() {
        TeamEntity team = TeamEntity.builder().id(10L).name("Team").ownerId(1L).build();
        TeamMemberEntity owner = TeamMemberEntity.builder()
                .teamId(10L).userId(1L).role(TeamService.ROLE_OWNER).status(1).build();
        when(teamRepository.findById(10L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndStatus(10L, 1L, 1)).thenReturn(Optional.of(owner));

        teamService.deleteTeam(1L, 10L);

        assertThat(team.getDeletedAt()).isNotNull();
        assertThat(team.getDeletedBy()).isEqualTo(1L);
        assertThat(team.getPurgeAfter()).isNotNull();
        verify(teamRepository).save(team);
        verify(teamAuditService).record(10L, 1L, "TEAM_DELETE", "TEAM", 10L, "Team");
        verify(notificationService).notifyTeamMembers(eq(10L), eq(1L), eq("TEAM_DELETE"), anyString(), anyString(), isNull(), eq(true));
    }

    @Test
    void deleteTeam_shouldBeIdempotentWhenAlreadyDeleted() {
        TeamEntity team = TeamEntity.builder()
                .id(10L)
                .name("Team")
                .ownerId(1L)
                .deletedAt(LocalDateTime.now())
                .deletedBy(1L)
                .build();
        TeamMemberEntity owner = TeamMemberEntity.builder()
                .teamId(10L).userId(1L).role(TeamService.ROLE_OWNER).status(1).build();
        when(teamRepository.findById(10L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndStatus(10L, 1L, 1)).thenReturn(Optional.of(owner));

        teamService.deleteTeam(1L, 10L);

        verify(teamRepository, never()).save(any());
        verify(teamAuditService, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyLong(), any());
        verify(notificationService, never()).notifyTeamMembers(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void transferOwner_shouldPromoteTargetAndDemoteCurrentOwner() {
        TeamEntity team = TeamEntity.builder().id(10L).name("Team").ownerId(1L).build();
        TeamMemberEntity owner = TeamMemberEntity.builder()
                .teamId(10L).userId(1L).role(TeamService.ROLE_OWNER).status(1).build();
        TeamMemberEntity target = TeamMemberEntity.builder()
                .teamId(10L).userId(2L).role(TeamService.ROLE_EDITOR).status(1).build();
        when(teamRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndStatus(10L, 1L, 1)).thenReturn(Optional.of(owner));
        when(teamMemberRepository.findByTeamIdAndUserIdAndStatus(10L, 2L, 1)).thenReturn(Optional.of(target));
        when(teamRepository.save(any(TeamEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TeamView view = teamService.transferOwner(1L, 10L, 2L);

        assertThat(team.getOwnerId()).isEqualTo(2L);
        assertThat(owner.getRole()).isEqualTo(TeamService.ROLE_ADMIN);
        assertThat(target.getRole()).isEqualTo(TeamService.ROLE_OWNER);
        assertThat(view.role()).isEqualTo(TeamService.ROLE_ADMIN);
    }
}
