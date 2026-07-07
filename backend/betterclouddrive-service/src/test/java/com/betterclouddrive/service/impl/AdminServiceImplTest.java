package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.TeamMemberRepository;
import com.betterclouddrive.dal.repository.TeamOperationLogRepository;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamPurgeService;
import com.betterclouddrive.service.dto.AdminTeamRecoveryDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class AdminServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private TeamOperationLogRepository teamOperationLogRepository;
    @Mock private FileRepository fileRepository;
    @Mock private TeamAuditService teamAuditService;
    @Mock private TeamPurgeService teamPurgeService;
    @Mock private NotificationService notificationService;
    @InjectMocks private AdminServiceImpl adminService;

    @Test
    void listUsers_shouldReturnPageWithoutFilter() {
        UserEntity u = UserEntity.builder().id(1L).username("alice").build();
        Page<UserEntity> page = new PageImpl<>(List.of(u), PageRequest.of(0, 10), 1);
        when(userRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        PageResult<UserEntity> result = adminService.listUsers(null, null, 1, 10);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1);
    }

    @Test
    void listUsers_shouldFilterByKeyword() {
        Page<UserEntity> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(userRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        PageResult<UserEntity> result = adminService.listUsers("alice", null, 1, 10);

        assertThat(result.getRecords()).isEmpty();
        verify(userRepository).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void listUsers_shouldFilterByStatus() {
        Page<UserEntity> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(userRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        adminService.listUsers(null, 1, 1, 10);

        verify(userRepository).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void updateUserStatus_shouldSetNewStatus() {
        UserEntity user = UserEntity.builder().id(1L).status(1).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);

        adminService.updateUserStatus(1L, 0);

        assertThat(user.getStatus()).isEqualTo(0);
        verify(userRepository).save(user);
    }

    @Test
    void updateUserStatus_shouldThrowWhenUserNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.updateUserStatus(99L, 0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void updateUserQuota_shouldSetNewQuota() {
        UserEntity user = UserEntity.builder().id(1L).storageQuota(10737418240L).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);

        adminService.updateUserQuota(1L, 21474836480L);

        assertThat(user.getStorageQuota()).isEqualTo(21474836480L);
        verify(userRepository).save(user);
    }

    @Test
    void purgeTeam_shouldBeIdempotentWhenTeamAlreadyPurged() {
        when(teamRepository.findById(10L)).thenReturn(Optional.empty());

        adminService.purgeTeam(1L, 10L);

        verify(teamPurgeService).purgeTeam(10L);
        verify(teamAuditService, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void purgeTeam_shouldRejectActiveTeam() {
        TeamEntity team = TeamEntity.builder().id(10L).name("Team").build();
        when(teamRepository.findById(10L)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> adminService.purgeTeam(1L, 10L))
                .isInstanceOf(BusinessException.class);

        verify(teamPurgeService, never()).purgeTeam(anyLong());
    }

    @Test
    void restoreTeam_shouldReturnActiveTeamWithoutSideEffects() {
        TeamEntity team = TeamEntity.builder().id(10L).name("Team").build();
        when(teamRepository.findById(10L)).thenReturn(Optional.of(team));

        TeamEntity restored = adminService.restoreTeam(1L, 10L);

        assertThat(restored).isSameAs(team);
        verify(teamRepository, never()).save(any());
        verify(teamAuditService, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void getTeamRecoveryDetails_shouldReturnDeletionDecisionContext() {
        LocalDateTime deletedAt = LocalDateTime.now().minusDays(2);
        LocalDateTime purgeAfter = LocalDateTime.now().plusDays(28);
        TeamEntity team = TeamEntity.builder()
                .id(10L)
                .name("Product")
                .deletedAt(deletedAt)
                .deletedBy(2L)
                .purgeAfter(purgeAfter)
                .build();
        TeamOperationLogEntity deleteLog = TeamOperationLogEntity.builder()
                .id(99L)
                .teamId(10L)
                .action("TEAM_DELETE")
                .details("Product")
                .createdAt(deletedAt)
                .build();
        when(teamRepository.findById(10L)).thenReturn(Optional.of(team));
        when(fileRepository.countByTeamIdAndIsDeletedFalse(10L)).thenReturn(12L);
        when(fileRepository.countByTeamIdAndIsDeletedTrue(10L)).thenReturn(3L);
        when(teamMemberRepository.countByTeamIdAndStatus(10L, 1)).thenReturn(4L);
        when(teamOperationLogRepository.findTop5ByTeamIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(deleteLog));
        when(teamOperationLogRepository.findFirstByTeamIdAndActionOrderByCreatedAtDesc(10L, "TEAM_DELETE"))
                .thenReturn(Optional.of(deleteLog));
        when(userRepository.findById(2L)).thenReturn(Optional.of(UserEntity.builder().id(2L).username("owner").build()));

        AdminTeamRecoveryDetails details = adminService.getTeamRecoveryDetails(10L);

        assertThat(details.teamId()).isEqualTo(10L);
        assertThat(details.teamName()).isEqualTo("Product");
        assertThat(details.deletedAt()).isEqualTo(deletedAt);
        assertThat(details.deletedBy()).isEqualTo(2L);
        assertThat(details.deletedByUsername()).isEqualTo("owner");
        assertThat(details.deleteReason()).isEqualTo("团队所有者删除：Product");
        assertThat(details.purgeAfter()).isEqualTo(purgeAfter);
        assertThat(details.activeFileCount()).isEqualTo(12L);
        assertThat(details.deletedFileCount()).isEqualTo(3L);
        assertThat(details.memberCount()).isEqualTo(4L);
        assertThat(details.restoreAvailable()).isTrue();
        assertThat(details.purgeAvailable()).isTrue();
        assertThat(details.recentAuditLogs()).containsExactly(deleteLog);
    }

    @Test
    void getSystemStats_shouldReturnCountsAndStorage() {
        when(userRepository.count(any(Specification.class))).thenReturn(10L, 8L);
        when(userRepository.selectTotalStorageUsed()).thenReturn(5368709120L);

        Map<String, Object> stats = adminService.getSystemStats();

        assertThat(stats).containsKey("totalUsers");
        assertThat(stats).containsKey("activeUsers");
        assertThat(stats).containsKey("totalStorageUsed");
        assertThat(stats.get("totalUsers")).isEqualTo(10L);
        assertThat(stats.get("activeUsers")).isEqualTo(8L);
        assertThat(stats.get("totalStorageUsed")).isEqualTo(5368709120L);
    }
}
