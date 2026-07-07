package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import com.betterclouddrive.dal.repository.TeamOperationLogRepository;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.TeamService;
import com.betterclouddrive.service.dto.TeamActivityItem;
import com.betterclouddrive.service.dto.TeamEffectivePermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class TeamActivityServiceImplTest {

    @Mock
    private TeamOperationLogRepository teamOperationLogRepository;

    @Mock
    private TeamService teamService;

    @Mock
    private TeamPermissionService teamPermissionService;

    @InjectMocks
    private TeamActivityServiceImpl teamActivityService;

    @Test
    void listActivities_shouldFilterFileAndShareActivitiesByReadPermission() {
        TeamOperationLogEntity visibleFile = log(1L, "FILE_RENAME", "FILE", 7L, "report.docx");
        TeamOperationLogEntity hiddenFile = log(2L, "FILE_RENAME", "FILE", 8L, "secret.docx");
        TeamOperationLogEntity visibleShare = log(3L, "SHARE_CREATE", "SHARE", 9L, "share abc file 7");
        TeamOperationLogEntity teamUpdate = log(4L, "TEAM_UPDATE", "TEAM", 10L, "Product");
        when(teamService.requireRead(2L, 10L))
                .thenReturn(TeamMemberEntity.builder().teamId(10L).userId(2L).role("VIEWER").status(1).build());
        when(teamOperationLogRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(visibleFile, hiddenFile, visibleShare, teamUpdate), PageRequest.of(0, 100), 4));
        when(teamPermissionService.effectivePermissions(2L, 10L, 7L))
                .thenReturn(new TeamEffectivePermission(true, false, false, false, false));
        when(teamPermissionService.effectivePermissions(2L, 10L, 8L))
                .thenReturn(new TeamEffectivePermission(false, false, false, false, false));

        PageResult<TeamActivityItem> result = teamActivityService.listActivities(2L, 10L, 1, 20);

        assertThat(result.getRecords()).extracting(TeamActivityItem::id).containsExactly(1L, 3L, 4L);
        assertThat(result.getTotal()).isEqualTo(3);
        verify(teamService).requireRead(2L, 10L);
        verify(teamPermissionService).effectivePermissions(2L, 10L, 7L);
        verify(teamPermissionService).effectivePermissions(2L, 10L, 8L);
    }

    @Test
    void listActivities_shouldReturnSanitizedInviteSummary() {
        TeamOperationLogEntity invite = log(5L, "INVITE_CREATE", "INVITE", 6L, "member@test.local as VIEWER");
        when(teamService.requireRead(2L, 10L))
                .thenReturn(TeamMemberEntity.builder().teamId(10L).userId(2L).role("VIEWER").status(1).build());
        when(teamOperationLogRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(invite), PageRequest.of(0, 100), 1));

        PageResult<TeamActivityItem> result = teamActivityService.listActivities(2L, 10L, 1, 20);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().getFirst().summary()).isEqualTo("团队邀请状态已更新");
    }

    @Test
    void listActivities_shouldPageVisibleActivitiesAfterFiltering() {
        when(teamService.requireRead(2L, 10L))
                .thenReturn(TeamMemberEntity.builder().teamId(10L).userId(2L).role("VIEWER").status(1).build());
        when(teamOperationLogRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(
                        log(1L, "TEAM_UPDATE", "TEAM", 10L, "A"),
                        log(2L, "TEAM_UPDATE", "TEAM", 10L, "B"),
                        log(3L, "TEAM_UPDATE", "TEAM", 10L, "C")), PageRequest.of(0, 100), 3));

        PageResult<TeamActivityItem> result = teamActivityService.listActivities(2L, 10L, 2, 2);

        assertThat(result.getRecords()).extracting(TeamActivityItem::id).containsExactly(3L);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getPages()).isEqualTo(2);
    }

    private TeamOperationLogEntity log(Long id, String action, String targetType, Long targetId, String details) {
        return TeamOperationLogEntity.builder()
                .id(id)
                .teamId(10L)
                .actorUserId(1L)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .result(1)
                .statusCode(200)
                .details(details)
                .createdAt(LocalDateTime.now().minusMinutes(id))
                .build();
    }
}
