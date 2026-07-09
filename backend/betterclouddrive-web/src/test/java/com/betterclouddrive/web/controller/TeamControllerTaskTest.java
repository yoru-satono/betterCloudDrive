package com.betterclouddrive.web.controller;

import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.service.FavoriteService;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.FileVersionService;
import com.betterclouddrive.service.FolderZipDownloadService;
import com.betterclouddrive.service.ShareService;
import com.betterclouddrive.service.TagService;
import com.betterclouddrive.service.TeamActivityService;
import com.betterclouddrive.service.TeamAuditExportTaskService;
import com.betterclouddrive.service.TeamAuditService;
import com.betterclouddrive.service.TeamInviteService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.TeamService;
import com.betterclouddrive.service.TeamTaskService;
import com.betterclouddrive.service.UploadService;
import com.betterclouddrive.service.dto.TeamTaskDownload;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.web.download.DownloadTicketService;
import com.betterclouddrive.web.export.AuditCsvExporter;
import com.betterclouddrive.web.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamControllerTaskTest {

    @Mock private TeamService teamService;
    @Mock private FileService fileService;
    @Mock private FileVersionService fileVersionService;
    @Mock private UploadService uploadService;
    @Mock private StorageService storageService;
    @Mock private FolderZipDownloadService folderZipDownloadService;
    @Mock private DownloadTicketService downloadTicketService;
    @Mock private ShareService shareService;
    @Mock private TeamInviteService teamInviteService;
    @Mock private TeamActivityService teamActivityService;
    @Mock private TeamAuditExportTaskService teamAuditExportTaskService;
    @Mock private TeamAuditService teamAuditService;
    @Mock private TagService tagService;
    @Mock private FavoriteService favoriteService;
    @Mock private TeamPermissionService teamPermissionService;
    @Mock private TeamTaskService teamTaskService;
    @Mock private AuditCsvExporter auditCsvExporter;
    @InjectMocks private TeamController controller;

    @Test
    void queueAuditExport_shouldCreatePendingTask() {
        LocalDateTime startAt = LocalDateTime.of(2026, 7, 9, 12, 0);
        LocalDateTime endAt = startAt.plusHours(1);
        TeamTaskEntity task = TeamTaskEntity.builder().id(88L).teamId(10L).status(TeamTaskEntity.STATUS_PENDING).build();
        when(teamAuditExportTaskService.queueExport(2L, 10L, 5L, "FILE_DELETE", "FILE", 1, startAt, endAt))
                .thenReturn(task);

        var response = controller.queueAuditExport(user(), 10L, 5L, "FILE_DELETE", "FILE", 1, startAt, endAt);

        assertThat(response.getData()).isSameAs(task);
    }

    @Test
    void downloadTaskResult_shouldStreamResultFile() {
        when(teamAuditExportTaskService.downloadExportResult(2L, 10L, 88L))
                .thenReturn(new TeamTaskDownload(
                        "team-10-audit.csv",
                        "text/csv;charset=UTF-8",
                        3L,
                        new ByteArrayInputStream("csv".getBytes())));

        ResponseEntity<StreamingResponseBody> response = controller.downloadTaskResult(user(), 10L, 88L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("team-10-audit.csv");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3L);
    }

    @Test
    void emptyRecycleBin_shouldRecordSuccessfulCleanupTask() {
        when(teamTaskService.startTask(10L, 2L, "TEAM_CLEANUP", "TEAM", 10L, "清空团队回收站"))
                .thenReturn(TeamTaskEntity.builder().id(88L).build());

        controller.emptyRecycleBin(user(), 10L);

        verify(teamService).requireManageMembers(2L, 10L);
        verify(fileService).emptyTeamRecycleBin(10L, 2L);
        verify(teamTaskService).markSucceeded(88L, "团队回收站已清空");
    }

    @Test
    void emptyRecycleBin_shouldRecordFailedCleanupTask() {
        when(teamTaskService.startTask(10L, 2L, "TEAM_CLEANUP", "TEAM", 10L, "清空团队回收站"))
                .thenReturn(TeamTaskEntity.builder().id(88L).build());
        doThrow(new RuntimeException("storage down")).when(fileService).emptyTeamRecycleBin(10L, 2L);

        assertThatThrownBy(() -> controller.emptyRecycleBin(user(), 10L))
                .isInstanceOf(RuntimeException.class);

        verify(teamTaskService).markFailed(88L, "storage down");
    }

    private UserPrincipal user() {
        return new UserPrincipal(2L, "chain", "jti", "USER");
    }
}
