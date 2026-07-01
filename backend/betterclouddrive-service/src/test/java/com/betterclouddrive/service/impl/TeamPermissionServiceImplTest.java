package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TeamFilePermissionEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.TeamFilePermissionRepository;
import com.betterclouddrive.dal.repository.TeamMemberRepository;
import com.betterclouddrive.service.NotificationService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class TeamPermissionServiceImplTest {

    @Mock private TeamService teamService;
    @Mock private FileRepository fileRepository;
    @Mock private TeamFilePermissionRepository permissionRepository;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private TeamAuditService teamAuditService;
    @Mock private NotificationService notificationService;
    @InjectMocks private TeamPermissionServiceImpl permissionService;

    @ParameterizedTest
    @CsvSource({
            "true,false,false,false",
            "false,true,false,false",
            "false,false,true,false",
            "false,false,false,true"
    })
    void upsertPermission_shouldRejectDerivedPermissionWithoutRead(
            boolean canWrite,
            boolean canShare,
            boolean canDelete,
            boolean canManage) {
        FileEntity file = teamFile();
        TeamMemberEntity actor = TeamMemberEntity.builder().teamId(10L).userId(2L).role("ADMIN").status(1).build();
        when(teamService.requireRead(2L, 10L)).thenReturn(actor);
        when(fileRepository.findById(7L)).thenReturn(Optional.of(file));
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectUserId(anyLong(), anyLong(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectRole(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> permissionService.upsertPermission(
                2L, 10L, 7L, "ROLE", null, "VIEWER",
                false, canWrite, canShare, canDelete, canManage))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("require read permission");

        verify(fileRepository).findById(7L);
        verify(permissionRepository, never()).save(any());
        verify(notificationService, never()).notifyUser(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void upsertPermission_shouldAllowReadOnlyPermission() {
        FileEntity file = teamFile();
        TeamMemberEntity actor = TeamMemberEntity.builder().teamId(10L).userId(2L).role("ADMIN").status(1).build();
        when(teamService.requireRead(2L, 10L)).thenReturn(actor);
        when(fileRepository.findById(7L)).thenReturn(Optional.of(file));
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectUserId(anyLong(), anyLong(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectRole(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(permissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamMemberRepository.findByTeamIdAndStatusOrderByCreatedAtAsc(10L, 1))
                .thenReturn(List.of(actor));

        TeamFilePermissionEntity saved = permissionService.upsertPermission(
                2L, 10L, 7L, "ROLE", null, "VIEWER",
                true, false, false, false, false);

        assertThat(saved.getCanRead()).isTrue();
        assertThat(saved.getCanWrite()).isFalse();
        assertThat(saved.getCanShare()).isFalse();
        assertThat(saved.getCanDelete()).isFalse();
        assertThat(saved.getCanManage()).isFalse();
    }

    @Test
    void upsertPermission_shouldRejectRemovingOwnManagePermission() {
        FileEntity file = teamFile();
        TeamMemberEntity actor = TeamMemberEntity.builder().teamId(10L).userId(2L).role("ADMIN").status(1).build();
        when(teamService.requireRead(2L, 10L)).thenReturn(actor);
        when(fileRepository.findById(7L)).thenReturn(Optional.of(file));
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectUserId(anyLong(), anyLong(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectRole(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> permissionService.upsertPermission(
                2L, 10L, 7L, "USER", 2L, null,
                true, true, true, true, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cannot remove your own manage permission");

        verify(permissionRepository, never()).save(any());
        verify(notificationService, never()).notifyUser(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void upsertPermission_shouldNotifyAffectedRoleMembersExceptActor() {
        FileEntity file = teamFile();
        TeamMemberEntity actor = TeamMemberEntity.builder().teamId(10L).userId(2L).role("ADMIN").status(1).build();
        TeamMemberEntity editor = TeamMemberEntity.builder().teamId(10L).userId(3L).role("EDITOR").status(1).build();
        TeamMemberEntity otherEditor = TeamMemberEntity.builder().teamId(10L).userId(4L).role("EDITOR").status(1).build();
        TeamMemberEntity viewer = TeamMemberEntity.builder().teamId(10L).userId(5L).role("VIEWER").status(1).build();
        when(teamService.requireRead(2L, 10L)).thenReturn(actor);
        when(fileRepository.findById(7L)).thenReturn(Optional.of(file));
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectUserId(anyLong(), anyLong(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectRole(anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(permissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamMemberRepository.findByTeamIdAndStatusOrderByCreatedAtAsc(10L, 1))
                .thenReturn(List.of(actor, editor, otherEditor, viewer));

        TeamFilePermissionEntity saved = permissionService.upsertPermission(
                2L, 10L, 7L, "ROLE", null, "EDITOR",
                true, false, false, false, false);

        assertThat(saved.getSubjectType()).isEqualTo("ROLE");
        assertThat(saved.getSubjectRole()).isEqualTo("EDITOR");
        verify(notificationService).notifyUser(eq(3L), eq(10L), eq("TEAM_PERMISSION_UPDATE"), anyString(), anyString(), anyString(), eq(true));
        verify(notificationService).notifyUser(eq(4L), eq(10L), eq("TEAM_PERMISSION_UPDATE"), anyString(), anyString(), anyString(), eq(true));
        verify(notificationService, never()).notifyUser(eq(2L), anyLong(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(notificationService, never()).notifyUser(eq(5L), anyLong(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    private FileEntity teamFile() {
        return FileEntity.builder()
                .id(7L)
                .teamId(10L)
                .fileName("roadmap.docx")
                .isDeleted(false)
                .build();
    }
}
