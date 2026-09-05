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
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamNotificationDispatchService;
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
    @Mock private TeamNotificationDispatchService teamNotificationDispatchService;
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
        verify(teamNotificationDispatchService, never()).enqueueUserNotification(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
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
        when(permissionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
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
        verify(teamNotificationDispatchService, never()).enqueueUserNotification(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
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
        when(permissionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamMemberRepository.findByTeamIdAndStatusOrderByCreatedAtAsc(10L, 1))
                .thenReturn(List.of(actor, editor, otherEditor, viewer));

        TeamFilePermissionEntity saved = permissionService.upsertPermission(
                2L, 10L, 7L, "ROLE", null, "EDITOR",
                true, false, false, false, false);

        assertThat(saved.getSubjectType()).isEqualTo("ROLE");
        assertThat(saved.getSubjectRole()).isEqualTo("EDITOR");
        verify(teamNotificationDispatchService).enqueueUserNotification(eq(3L), eq(10L), eq("TEAM_PERMISSION_UPDATE"), anyString(), anyString(), anyString(), eq(true));
        verify(teamNotificationDispatchService).enqueueUserNotification(eq(4L), eq(10L), eq("TEAM_PERMISSION_UPDATE"), anyString(), anyString(), anyString(), eq(true));
        verify(teamNotificationDispatchService, never()).enqueueUserNotification(eq(2L), anyLong(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(teamNotificationDispatchService, never()).enqueueUserNotification(eq(5L), anyLong(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void effectivePermissions_shouldExplainInheritedRoleOverride() {
        FileEntity child = teamFile();
        FileEntity folder = FileEntity.builder()
                .id(6L)
                .teamId(10L)
                .fileName("Docs")
                .fileType("folder")
                .isDeleted(false)
                .build();
        child.setParentId(folder.getId());
        TeamMemberEntity editor = TeamMemberEntity.builder().teamId(10L).userId(3L).role("EDITOR").status(1).build();
        TeamFilePermissionEntity roleOverride = TeamFilePermissionEntity.builder()
                .teamId(10L)
                .fileId(folder.getId())
                .subjectType("ROLE")
                .subjectRole("EDITOR")
                .canRead(true)
                .canWrite(false)
                .canShare(false)
                .canDelete(false)
                .canManage(false)
                .build();
        when(teamService.requireRead(3L, 10L)).thenReturn(editor);
        when(fileRepository.findById(7L)).thenReturn(Optional.of(child));
        when(fileRepository.findById(6L)).thenReturn(Optional.of(folder));
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectUserId(10L, 7L, "USER", 3L))
                .thenReturn(Optional.empty());
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectRole(10L, 7L, "ROLE", "EDITOR"))
                .thenReturn(Optional.empty());
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectUserId(10L, 6L, "USER", 3L))
                .thenReturn(Optional.empty());
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectRole(10L, 6L, "ROLE", "EDITOR"))
                .thenReturn(Optional.of(roleOverride));

        var effective = permissionService.effectivePermissions(3L, 10L, 7L);

        assertThat(effective.canRead()).isTrue();
        assertThat(effective.canWrite()).isFalse();
        assertThat(effective.sourceType()).isEqualTo("ROLE_OVERRIDE");
        assertThat(effective.sourceChain()).hasSize(2);
        assertThat(effective.sourceChain().get(0).type()).isEqualTo("BASE_ROLE");
        var source = effective.sourceChain().get(1);
        assertThat(source.type()).isEqualTo("ROLE_OVERRIDE");
        assertThat(source.fileId()).isEqualTo(6L);
        assertThat(source.fileName()).isEqualTo("Docs");
        assertThat(source.subjectRole()).isEqualTo("EDITOR");
        assertThat(source.inherited()).isTrue();
    }

    @Test
    void deletePermission_shouldRollbackWhenItWouldRemoveActorManagePermission() {
        FileEntity file = teamFile();
        TeamMemberEntity actor = TeamMemberEntity.builder().teamId(10L).userId(2L).role("EDITOR").status(1).build();
        TeamFilePermissionEntity ownOverride = TeamFilePermissionEntity.builder()
                .id(9L).teamId(10L).fileId(7L).subjectType("USER").subjectUserId(2L)
                .canRead(true).canWrite(true).canShare(true).canDelete(true).canManage(true).build();
        when(teamService.requireRead(2L, 10L)).thenReturn(actor);
        when(fileRepository.findById(7L)).thenReturn(Optional.of(file));
        when(permissionRepository.findById(9L)).thenReturn(Optional.of(ownOverride));
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectUserId(10L, 7L, "USER", 2L))
                .thenReturn(Optional.of(ownOverride), Optional.empty());
        when(permissionRepository.findByTeamIdAndFileIdAndSubjectTypeAndSubjectRole(10L, 7L, "ROLE", "EDITOR"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> permissionService.deletePermission(2L, 10L, 7L, 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("remove your own manage permission");

        verify(permissionRepository).delete(ownOverride);
        verify(permissionRepository).flush();
        verify(teamAuditService, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyLong(), any());
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
