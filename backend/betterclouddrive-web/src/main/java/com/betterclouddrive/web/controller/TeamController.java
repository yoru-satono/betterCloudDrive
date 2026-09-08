package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FileVersionEntity;
import com.betterclouddrive.dal.entity.FolderZipDownloadCacheEntity;
import com.betterclouddrive.dal.entity.ShareLinkEntity;
import com.betterclouddrive.dal.entity.TagEntity;
import com.betterclouddrive.dal.entity.TeamMemberEntity;
import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.entity.UploadSessionEntity;
import com.betterclouddrive.service.FavoriteService;
import com.betterclouddrive.service.FileVersionService;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.FolderZipDownloadService;
import com.betterclouddrive.service.FolderZipTaskService;
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
import com.betterclouddrive.service.dto.TeamActivityItem;
import com.betterclouddrive.service.dto.TeamEffectivePermission;
import com.betterclouddrive.service.dto.TeamTaskDownload;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.web.download.ByteRange;
import com.betterclouddrive.web.download.DownloadTicketService;
import com.betterclouddrive.web.download.DownloadTicketType;
import com.betterclouddrive.web.dto.request.*;
import com.betterclouddrive.web.dto.response.DownloadTicketResponse;
import com.betterclouddrive.web.dto.response.ShareLinkResponse;
import com.betterclouddrive.web.dto.response.ShareLinkResponseMapper;
import com.betterclouddrive.web.dto.response.SharePasswordResponse;
import com.betterclouddrive.web.dto.response.TeamInviteResponse;
import com.betterclouddrive.web.dto.response.TeamMemberResponse;
import com.betterclouddrive.web.dto.response.TeamFilePermissionResponse;
import com.betterclouddrive.web.dto.response.TeamResponse;
import com.betterclouddrive.web.export.AuditCsvExporter;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {

    private static final int AUDIT_EXPORT_LIMIT = 10000;

    private final TeamService teamService;
    private final FileService fileService;
    private final FileVersionService fileVersionService;
    private final UploadService uploadService;
    private final StorageService storageService;
    private final FolderZipDownloadService folderZipDownloadService;
    private final FolderZipTaskService folderZipTaskService;
    private final DownloadTicketService downloadTicketService;
    private final ShareService shareService;
    private final ShareLinkResponseMapper shareResponseMapper;
    private final TeamInviteService teamInviteService;
    private final TeamActivityService teamActivityService;
    private final TeamAuditExportTaskService teamAuditExportTaskService;
    private final TeamAuditService teamAuditService;
    private final TagService tagService;
    private final FavoriteService favoriteService;
    private final TeamPermissionService teamPermissionService;
    private final TeamTaskService teamTaskService;
    private final AuditCsvExporter auditCsvExporter;

    @GetMapping
    public ApiResponse<PageResult<TeamResponse>> listTeams(
            @CurrentUser UserPrincipal user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        PageResult<com.betterclouddrive.service.dto.TeamView> result = teamService.listTeams(user.getUserId(), page, size);
        return ApiResponse.success(PageResult.of(result.getRecords().stream().map(TeamResponse::from).toList(),
                result.getTotal(), result.getPage(), result.getSize()));
    }

    @PostMapping
    public ApiResponse<TeamResponse> createTeam(
            @CurrentUser UserPrincipal user,
            @Valid @RequestBody CreateTeamRequest request) {
        return ApiResponse.success(TeamResponse.from(teamService.createTeam(user.getUserId(), request.getName())));
    }

    @GetMapping("/{teamId}")
    public ApiResponse<TeamResponse> getTeam(@CurrentUser UserPrincipal user, @PathVariable Long teamId) {
        return ApiResponse.success(TeamResponse.from(teamService.getTeam(user.getUserId(), teamId)));
    }

    @PutMapping("/{teamId}")
    public ApiResponse<TeamResponse> updateTeam(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @Valid @RequestBody UpdateTeamRequest request) {
        return ApiResponse.success(TeamResponse.from(teamService.updateTeam(user.getUserId(), teamId, request.getName())));
    }

    @DeleteMapping("/{teamId}")
    public ApiResponse<Void> deleteTeam(@CurrentUser UserPrincipal user, @PathVariable Long teamId) {
        teamService.deleteTeam(user.getUserId(), teamId);
        return ApiResponse.success();
    }

    @PostMapping("/{teamId}/leave")
    public ApiResponse<Void> leaveTeam(@CurrentUser UserPrincipal user, @PathVariable Long teamId) {
        teamService.leaveTeam(user.getUserId(), teamId);
        return ApiResponse.success();
    }

    @PostMapping("/{teamId}/owner")
    public ApiResponse<TeamResponse> transferOwner(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @Valid @RequestBody TransferTeamOwnerRequest request) {
        return ApiResponse.success(TeamResponse.from(teamService.transferOwner(user.getUserId(), teamId, request.getUserId())));
    }

    @GetMapping("/{teamId}/members")
    public ApiResponse<PageResult<TeamMemberResponse>> listMembers(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        PageResult<com.betterclouddrive.service.dto.TeamMemberView> result = teamService.listMembers(user.getUserId(), teamId, page, size);
        return ApiResponse.success(PageResult.of(result.getRecords().stream().map(TeamMemberResponse::from).toList(),
                result.getTotal(), result.getPage(), result.getSize()));
    }

    @PostMapping("/{teamId}/members")
    public ApiResponse<TeamMemberResponse> addMember(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @Valid @RequestBody AddTeamMemberRequest request) {
        return ApiResponse.success(TeamMemberResponse.from(teamService.addMember(
                user.getUserId(), teamId, request.getUsername(), request.getRole())));
    }

    @PutMapping("/{teamId}/members/{userId}")
    public ApiResponse<TeamMemberResponse> updateMember(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateTeamMemberRequest request) {
        return ApiResponse.success(TeamMemberResponse.from(teamService.updateMember(
                user.getUserId(), teamId, userId, request.getRole())));
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    public ApiResponse<Void> removeMember(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long userId) {
        teamService.removeMember(user.getUserId(), teamId, userId);
        return ApiResponse.success();
    }

    @PostMapping("/{teamId}/invites")
    public ApiResponse<TeamInviteResponse> createInvite(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @Valid @RequestBody CreateTeamInviteRequest request) {
        return ApiResponse.success(TeamInviteResponse.from(teamInviteService.createInvite(
                user.getUserId(), teamId, request.getEmail(), request.getRole())));
    }

    @GetMapping("/{teamId}/invites")
    public ApiResponse<PageResult<TeamInviteResponse>> listInvites(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        PageResult<com.betterclouddrive.service.dto.TeamInviteView> result = teamInviteService.listInvites(user.getUserId(), teamId, page, size);
        return ApiResponse.success(PageResult.of(result.getRecords().stream().map(TeamInviteResponse::from).toList(),
                result.getTotal(), result.getPage(), result.getSize()));
    }

    @PostMapping("/{teamId}/invites/{inviteId}/resend")
    public ApiResponse<TeamInviteResponse> resendInvite(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long inviteId) {
        return ApiResponse.success(TeamInviteResponse.from(teamInviteService.resendInvite(user.getUserId(), teamId, inviteId)));
    }

    @DeleteMapping("/{teamId}/invites/{inviteId}")
    public ApiResponse<Void> cancelInvite(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long inviteId) {
        teamInviteService.cancelInvite(user.getUserId(), teamId, inviteId);
        return ApiResponse.success();
    }

    @GetMapping("/{teamId}/audit-logs")
    public ApiResponse<PageResult<TeamOperationLogEntity>> listAuditLogs(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        return ApiResponse.success(teamAuditService.listLogs(
                user.getUserId(), teamId, actorUserId, action, targetType, result, startAt, endAt, page, size));
    }

    @GetMapping("/{teamId}/activities")
    public ApiResponse<PageResult<TeamActivityItem>> listActivities(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        return ApiResponse.success(teamActivityService.listActivities(user.getUserId(), teamId, page, size));
    }

    @GetMapping(value = "/{teamId}/audit-logs/export", produces = "text/csv")
    public ResponseEntity<String> exportAuditLogs(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt) {
        TeamTaskEntity task = teamTaskService.startTask(
                teamId, user.getUserId(), "AUDIT_EXPORT", "TEAM", teamId, "Export team audit log");
        try {
            PageResult<TeamOperationLogEntity> logs = teamAuditService.exportLogs(
                    user.getUserId(), teamId, actorUserId, action, targetType, result, startAt, endAt, AUDIT_EXPORT_LIMIT);
            teamTaskService.markSucceeded(task.getId(), "Exported " + logs.getRecords().size() + " audit records");
            return auditCsvExporter.teamAuditResponse(teamId, logs.getRecords());
        } catch (RuntimeException e) {
            teamTaskService.markFailed(task.getId(), e.getMessage());
            throw e;
        }
    }

    @PostMapping("/{teamId}/audit-logs/export-tasks")
    public ApiResponse<TeamTaskEntity> queueAuditExport(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt) {
        return ApiResponse.success(teamAuditExportTaskService.queueExport(
                user.getUserId(), teamId, actorUserId, action, targetType, result, startAt, endAt));
    }

    @GetMapping("/{teamId}/tasks")
    public ApiResponse<PageResult<TeamTaskEntity>> listTasks(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        return ApiResponse.success(teamTaskService.listTasks(user.getUserId(), teamId, status, type, page, size));
    }

    @PostMapping("/{teamId}/tasks/{taskId}/retry")
    public ApiResponse<TeamTaskEntity> retryTask(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long taskId) {
        return ApiResponse.success(teamTaskService.retryTask(user.getUserId(), teamId, taskId));
    }

    @GetMapping("/{teamId}/tasks/{taskId}/download")
    public ResponseEntity<StreamingResponseBody> downloadTaskResult(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long taskId) {
        TeamTaskEntity task = teamTaskService.getTaskForUser(user.getUserId(), teamId, taskId);
        TeamTaskDownload download;
        if ("AUDIT_EXPORT".equals(task.getTaskType())) {
            download = teamAuditExportTaskService.downloadExportResult(user.getUserId(), teamId, taskId);
        } else if ("FOLDER_ZIP".equals(task.getTaskType())) {
            download = folderZipTaskService.downloadTeamFolderZipResult(user.getUserId(), teamId, taskId);
        } else {
            throw new BusinessException(ApiCode.NOT_FOUND, "Task result is not available");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, attachmentDisposition(download.fileName()))
                .body(outputStream -> {
                    try (InputStream inputStream = download.inputStream()) {
                        inputStream.transferTo(outputStream);
                    }
                });
    }

    @PostMapping("/{teamId}/favorites/{fileId}")
    public ApiResponse<Void> addTeamFavorite(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        teamPermissionService.requireRead(user.getUserId(), teamId, fileId);
        favoriteService.addTeamFavorite(user.getUserId(), teamId, fileId);
        return ApiResponse.success();
    }

    @DeleteMapping("/{teamId}/favorites/{fileId}")
    public ApiResponse<Void> removeTeamFavorite(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        favoriteService.removeTeamFavorite(user.getUserId(), teamId, fileId);
        return ApiResponse.success();
    }

    @GetMapping("/{teamId}/favorites/{fileId}/status")
    public ApiResponse<Boolean> isTeamFavorite(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        teamPermissionService.requireRead(user.getUserId(), teamId, fileId);
        return ApiResponse.success(favoriteService.isTeamFavorite(user.getUserId(), teamId, fileId));
    }

    @GetMapping("/{teamId}/favorites")
    public ApiResponse<PageResult<FileEntity>> listTeamFavorites(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        return ApiResponse.success(favoriteService.listTeamFavorites(user.getUserId(), teamId, q, page, size));
    }

    @PostMapping("/{teamId}/tags")
    public ApiResponse<TagEntity> createTeamTag(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @Valid @RequestBody CreateTagRequest request) {
        return ApiResponse.success(tagService.createTeamTag(user.getUserId(), teamId, request.getTagName(), request.getColor()));
    }

    @GetMapping("/{teamId}/tags")
    public ApiResponse<List<TagEntity>> listTeamTags(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam(required = false) String q) {
        return ApiResponse.success(tagService.listTeamTags(user.getUserId(), teamId, q));
    }

    @PutMapping("/{teamId}/tags/{tagId}")
    public ApiResponse<TagEntity> updateTeamTag(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long tagId,
            @Valid @RequestBody UpdateTagRequest request) {
        return ApiResponse.success(tagService.updateTeamTag(
                user.getUserId(), teamId, tagId, request.getTagName(), request.getColor()));
    }

    @DeleteMapping("/{teamId}/tags/{tagId}")
    public ApiResponse<Void> deleteTeamTag(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long tagId) {
        tagService.deleteTeamTag(user.getUserId(), teamId, tagId);
        return ApiResponse.success();
    }

    @PostMapping("/{teamId}/tags/{tagId}/files")
    public ApiResponse<Void> tagTeamFiles(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long tagId,
            @RequestBody Map<String, List<Long>> body) {
        List<Long> fileIds = body.get("fileIds");
        if (fileIds != null) {
            fileIds.forEach(fileId -> teamPermissionService.requireWrite(user.getUserId(), teamId, fileId));
        }
        tagService.tagTeamFiles(user.getUserId(), teamId, tagId, body.get("fileIds"));
        return ApiResponse.success();
    }

    @DeleteMapping("/{teamId}/tags/{tagId}/files/{fileId}")
    public ApiResponse<Void> untagTeamFile(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long tagId,
            @PathVariable Long fileId) {
        teamPermissionService.requireWrite(user.getUserId(), teamId, fileId);
        tagService.untagTeamFile(user.getUserId(), teamId, tagId, fileId);
        return ApiResponse.success();
    }

    @GetMapping("/{teamId}/tags/{tagId}/files")
    public ApiResponse<PageResult<FileEntity>> listTeamFilesByTag(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long tagId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        return ApiResponse.success(tagService.listTeamFilesByTag(user.getUserId(), teamId, tagId, q, page, size));
    }

    @GetMapping("/{teamId}/files/{fileId}/tags")
    public ApiResponse<List<TagEntity>> listTeamFileTags(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        return ApiResponse.success(tagService.listTeamFileTags(user.getUserId(), teamId, fileId));
    }

    @PutMapping("/{teamId}/files/{fileId}/tags")
    public ApiResponse<List<TagEntity>> replaceTeamFileTags(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId,
            @Valid @RequestBody ReplaceFileTagsRequest request) {
        return ApiResponse.success(tagService.replaceTeamFileTags(
                user.getUserId(), teamId, fileId, request.getTagIds()));
    }

    @GetMapping("/{teamId}/files")
    public ApiResponse<PageResult<FileEntity>> listFiles(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size,
            @RequestParam(defaultValue = "fileName") String sortBy,
            @RequestParam(defaultValue = "asc") String order) {
        if (parentId == null) {
            teamService.requireRead(user.getUserId(), teamId);
        } else {
            teamPermissionService.requireRead(user.getUserId(), teamId, parentId);
        }
        return ApiResponse.success(fileService.listTeamFiles(user.getUserId(), teamId, parentId, page, size, sortBy, order));
    }

    @GetMapping("/{teamId}/files/search")
    public ApiResponse<PageResult<FileEntity>> searchFiles(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        teamService.requireRead(user.getUserId(), teamId);
        return ApiResponse.success(fileService.searchTeamFiles(user.getUserId(), teamId, q, page, size));
    }

    @GetMapping("/{teamId}/files/{fileId}")
    public ApiResponse<FileEntity> getFile(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        teamPermissionService.requireRead(user.getUserId(), teamId, fileId);
        return ApiResponse.success(fileService.getTeamFile(teamId, fileId));
    }

    @GetMapping("/{teamId}/files/{fileId}/thumbnail")
    public ResponseEntity<StreamingResponseBody> thumbnail(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        teamPermissionService.requireRead(user.getUserId(), teamId, fileId);
        return thumbnailResponse(fileService.getTeamFile(teamId, fileId));
    }

    @GetMapping("/{teamId}/files/{fileId}/effective-permission")
    public ApiResponse<TeamEffectivePermission> effectivePermission(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        return ApiResponse.success(teamPermissionService.effectivePermissions(user.getUserId(), teamId, fileId));
    }

    @GetMapping("/{teamId}/files/{fileId}/permissions")
    public ApiResponse<List<TeamFilePermissionResponse>> listPermissions(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        return ApiResponse.success(teamPermissionService.listPermissions(user.getUserId(), teamId, fileId)
                .stream().map(TeamFilePermissionResponse::from).toList());
    }

    @PutMapping("/{teamId}/files/{fileId}/permissions")
    public ApiResponse<TeamFilePermissionResponse> upsertPermission(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId,
            @RequestBody UpdateTeamFilePermissionRequest request) {
        return ApiResponse.success(TeamFilePermissionResponse.from(teamPermissionService.upsertPermission(
                user.getUserId(), teamId, fileId,
                request.getSubjectType(), request.getSubjectUserId(), request.getSubjectRole(),
                Boolean.TRUE.equals(request.getCanRead()),
                Boolean.TRUE.equals(request.getCanWrite()),
                Boolean.TRUE.equals(request.getCanShare()),
                Boolean.TRUE.equals(request.getCanDelete()),
                Boolean.TRUE.equals(request.getCanManage()))));
    }

    @DeleteMapping("/{teamId}/files/{fileId}/permissions/{permissionId}")
    public ApiResponse<Void> deletePermission(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId,
            @PathVariable Long permissionId) {
        teamPermissionService.deletePermission(user.getUserId(), teamId, fileId, permissionId);
        return ApiResponse.success();
    }

    @PostMapping("/{teamId}/files/folder")
    public ApiResponse<FileEntity> createFolder(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @Valid @RequestBody CreateFolderRequest request) {
        teamPermissionService.requireParentWrite(user.getUserId(), teamId, request.getParentId());
        return ApiResponse.success(fileService.createTeamFolder(
                teamId, user.getUserId(), request.getParentId(), request.getFolderName()));
    }

    @PutMapping("/{teamId}/files/{fileId}")
    public ApiResponse<FileEntity> renameFile(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId,
            @Valid @RequestBody RenameRequest request) {
        teamPermissionService.requireWrite(user.getUserId(), teamId, fileId);
        return ApiResponse.success(fileService.renameTeamFile(teamId, user.getUserId(), fileId, request.getNewName()));
    }

    @PostMapping("/{teamId}/files/{fileId}/move")
    public ApiResponse<Void> moveFile(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId,
            @Valid @RequestBody MoveFileRequest request) {
        teamPermissionService.requireWrite(user.getUserId(), teamId, fileId);
        teamPermissionService.requireParentWrite(user.getUserId(), teamId, request.getTargetParentId());
        fileService.moveTeamFile(teamId, user.getUserId(), fileId, request.getTargetParentId());
        return ApiResponse.success();
    }

    @DeleteMapping("/{teamId}/files")
    public ApiResponse<Void> deleteFiles(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @Valid @RequestBody BatchDeleteRequest request) {
        request.getFileIds().forEach(fileId -> teamPermissionService.requireDelete(user.getUserId(), teamId, fileId));
        fileService.deleteTeamFiles(teamId, user.getUserId(), request.getFileIds());
        return ApiResponse.success();
    }

    @GetMapping("/{teamId}/recycle-bin")
    public ApiResponse<PageResult<FileEntity>> listRecycleBin(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        teamService.requireRead(user.getUserId(), teamId);
        return ApiResponse.success(fileService.listTeamRecycleBin(user.getUserId(), teamId, q, page, size));
    }

    @PostMapping("/{teamId}/recycle-bin/{fileId}/restore")
    public ApiResponse<Void> restoreFile(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        teamPermissionService.requireDelete(user.getUserId(), teamId, fileId);
        fileService.restoreTeamFile(teamId, user.getUserId(), fileId);
        return ApiResponse.success();
    }

    @DeleteMapping("/{teamId}/recycle-bin/{fileId}")
    public ApiResponse<Void> permanentDeleteFile(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        teamPermissionService.requireDelete(user.getUserId(), teamId, fileId);
        fileService.permanentDeleteTeamFile(teamId, user.getUserId(), fileId);
        return ApiResponse.success();
    }

    @DeleteMapping("/{teamId}/recycle-bin")
    public ApiResponse<Void> emptyRecycleBin(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId) {
        teamService.requireManageMembers(user.getUserId(), teamId);
        TeamTaskEntity task = teamTaskService.startTask(
                teamId, user.getUserId(), "TEAM_CLEANUP", "TEAM", teamId, "Empty team recycle bin");
        try {
            fileService.emptyTeamRecycleBin(teamId, user.getUserId());
            teamTaskService.markSucceeded(task.getId(), "Team recycle bin emptied");
        } catch (RuntimeException e) {
            teamTaskService.markFailed(task.getId(), e.getMessage());
            throw e;
        }
        return ApiResponse.success();
    }

    @GetMapping("/{teamId}/files/{fileId}/versions")
    public ApiResponse<List<FileVersionEntity>> listVersions(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        teamPermissionService.requireRead(user.getUserId(), teamId, fileId);
        return ApiResponse.success(fileVersionService.listTeamVersions(teamId, fileId));
    }

    @GetMapping("/{teamId}/files/{fileId}/versions/{versionNumber}/download")
    public ResponseEntity<StreamingResponseBody> downloadVersion(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId,
            @PathVariable int versionNumber,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        teamPermissionService.requireRead(user.getUserId(), teamId, fileId);
        FileEntity file = fileService.getTeamFile(teamId, fileId);
        FileVersionEntity version = fileVersionService.getTeamVersion(teamId, fileId, versionNumber);
        String mimeType = file.getMimeType() != null ? file.getMimeType() : "application/octet-stream";
        return rangedObjectResponse(version.getStoragePath(), version.getFileSize(), mimeType,
                attachmentDisposition(versionFileName(file.getFileName(), versionNumber)), rangeHeader);
    }

    @PostMapping("/{teamId}/files/{fileId}/versions/{versionNumber}/restore")
    public ApiResponse<FileEntity> restoreVersion(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId,
            @PathVariable int versionNumber) {
        teamPermissionService.requireWrite(user.getUserId(), teamId, fileId);
        return ApiResponse.success(fileVersionService.restoreTeamVersion(teamId, user.getUserId(), fileId, versionNumber));
    }

    @DeleteMapping("/{teamId}/files/{fileId}/versions/{versionNumber}")
    public ApiResponse<Void> deleteVersion(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId,
            @PathVariable int versionNumber) {
        teamPermissionService.requireDelete(user.getUserId(), teamId, fileId);
        fileVersionService.deleteTeamVersion(teamId, user.getUserId(), fileId, versionNumber);
        return ApiResponse.success();
    }

    @PostMapping("/{teamId}/upload/init")
    public ApiResponse<Map<String, Object>> initUpload(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @Valid @RequestBody InitUploadRequest request) {
        teamPermissionService.requireParentWrite(user.getUserId(), teamId, request.getParentId());
        UploadSessionEntity session = uploadService.initTeamUpload(
                teamId, user.getUserId(), request.getParentId(), request.getFileName(),
                request.getFileSize(), request.getMd5Hash(), request.getTotalChunks());
        return ApiResponse.success(Map.of(
                "sessionId", session.getId(),
                "chunkSize", session.getChunkSize(),
                "totalChunks", session.getTotalChunks()
        ));
    }

    @PostMapping("/{teamId}/upload/{sessionId}/chunk")
    public ApiResponse<Map<String, Object>> uploadChunk(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable String sessionId,
            @RequestParam int chunkNumber,
            @RequestParam(required = false) String chunkMd5,
            @RequestParam("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read chunk data", e);
        }
        uploadService.uploadTeamChunk(teamId, sessionId, user.getUserId(), chunkNumber, bytes, chunkMd5);
        return ApiResponse.success(Map.of("chunkNumber", chunkNumber));
    }

    @GetMapping("/{teamId}/upload/{sessionId}/status")
    public ApiResponse<Map<String, Object>> getUploadStatus(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable String sessionId) {
        return ApiResponse.success(uploadService.getTeamUploadStatus(teamId, sessionId, user.getUserId()));
    }

    @PostMapping("/{teamId}/upload/{sessionId}/complete")
    public ApiResponse<Map<String, Object>> completeUpload(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable String sessionId) {
        Long fileId = uploadService.completeTeamUpload(teamId, sessionId, user.getUserId());
        return ApiResponse.success(Map.of("fileId", fileId));
    }

    @PostMapping("/{teamId}/upload/{sessionId}/cancel")
    public ApiResponse<Void> cancelUpload(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable String sessionId) {
        uploadService.cancelTeamUpload(teamId, sessionId, user.getUserId());
        return ApiResponse.success();
    }

    @PostMapping("/{teamId}/upload/instant")
    public ApiResponse<Map<String, Object>> instantUpload(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @Valid @RequestBody InstantUploadRequest request) {
        teamPermissionService.requireParentWrite(user.getUserId(), teamId, request.getParentId());
        Long fileId = uploadService.instantTeamUpload(
                teamId, user.getUserId(), request.getParentId(), request.getFileName(),
                request.getFileSize(), request.getMd5Hash());
        return ApiResponse.success(Map.of("fileId", fileId, "instant", true));
    }

    @PostMapping("/{teamId}/shares")
    public ApiResponse<ShareLinkResponse> createShare(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @Valid @RequestBody CreateShareRequest request) {
        teamPermissionService.requireShare(user.getUserId(), teamId, request.getFileId());
        ShareLinkEntity share = shareService.createTeamShare(
                user.getUserId(), teamId, request.getFileId(), request.getPassword(),
                request.getExpireAt(), request.getMaxVisits());
        return ApiResponse.success(shareResponseMapper.map(share));
    }

    @GetMapping("/{teamId}/shares")
    public ApiResponse<PageResult<ShareLinkResponse>> listShares(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean passwordProtected,
            @RequestParam(required = false) String expirationStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") @Max(100) int size) {
        teamService.requireRead(user.getUserId(), teamId);
        PageResult<ShareLinkEntity> shares = shareService.listTeamShares(user.getUserId(), teamId, q, passwordProtected, expirationStatus, page, size);
        List<ShareLinkResponse> readableShares = shareResponseMapper.mapAll(shares.getRecords());
        return ApiResponse.success(PageResult.of(
                readableShares,
                shares.getTotal(),
                shares.getPage(),
                shares.getSize()));
    }

    @GetMapping("/{teamId}/shares/{shareId}")
    public ApiResponse<ShareLinkResponse> getShare(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long shareId) {
        ShareLinkEntity share = shareService.getTeamShare(teamId, shareId);
        teamPermissionService.requireRead(user.getUserId(), teamId, share.getFileId());
        return ApiResponse.success(shareResponseMapper.map(share));
    }

    @GetMapping("/{teamId}/shares/{shareId}/password")
    public ApiResponse<SharePasswordResponse> getSharePassword(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long shareId) {
        ShareLinkEntity share = shareService.getTeamShare(teamId, shareId);
        teamPermissionService.requireShare(user.getUserId(), teamId, share.getFileId());
        return ApiResponse.success(new SharePasswordResponse(shareService.getTeamSharePassword(teamId, shareId)));
    }

    @PutMapping("/{teamId}/shares/{shareId}")
    public ApiResponse<ShareLinkResponse> updateShare(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long shareId,
            @RequestBody UpdateShareRequest request) {
        ShareLinkEntity existing = shareService.getTeamShare(teamId, shareId);
        teamPermissionService.requireShare(user.getUserId(), teamId, existing.getFileId());
        TeamMemberEntity member = teamService.requireRead(user.getUserId(), teamId);
        ShareLinkEntity share = shareService.updateTeamShare(
                user.getUserId(), canManageAllShares(member, existing.getFileId()), teamId, shareId,
                request.toCommand());
        return ApiResponse.success(shareResponseMapper.map(share));
    }

    @DeleteMapping("/{teamId}/shares/{shareId}")
    public ApiResponse<Void> cancelShare(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long shareId) {
        ShareLinkEntity share = shareService.getTeamShare(teamId, shareId);
        teamPermissionService.requireShare(user.getUserId(), teamId, share.getFileId());
        TeamMemberEntity member = teamService.requireRead(user.getUserId(), teamId);
        shareService.cancelTeamShare(user.getUserId(), canManageAllShares(member, share.getFileId()), teamId, shareId);
        return ApiResponse.success();
    }

    @PostMapping("/{teamId}/download/{fileId}/ticket")
    public ApiResponse<DownloadTicketResponse> createFileDownloadTicket(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        teamPermissionService.requireRead(user.getUserId(), teamId, fileId);
        FileEntity file = fileService.getTeamFile(teamId, fileId);
        if (!"file".equals(file.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND, "Cannot download a folder");
        }
        String ticket = downloadTicketService.createTicket(user.getUserId(), fileId, DownloadTicketType.TEAM_FILE);
        return ApiResponse.success(new DownloadTicketResponse(
                "/api/v1/teams/" + teamId + "/download/" + fileId + "?ticket=" + ticket));
    }

    @PostMapping("/{teamId}/download/folders/{fileId}/zip/ticket")
    public ApiResponse<DownloadTicketResponse> createFolderZipDownloadTicket(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        teamPermissionService.requireRead(user.getUserId(), teamId, fileId);
        FileEntity folder = fileService.getTeamFile(teamId, fileId);
        folderZipDownloadService.validateDownloadable(folder);
        String ticket = downloadTicketService.createTicket(user.getUserId(), fileId, DownloadTicketType.TEAM_FOLDER_ZIP);
        return ApiResponse.success(new DownloadTicketResponse(
                "/api/v1/teams/" + teamId + "/download/folders/" + fileId + "/zip?ticket=" + ticket));
    }

    @PostMapping("/{teamId}/download/folders/{fileId}/zip/tasks")
    public ApiResponse<TeamTaskEntity> queueFolderZipTask(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        return ApiResponse.success(folderZipTaskService.queueTeamFolderZip(user.getUserId(), teamId, fileId));
    }

    @GetMapping("/{teamId}/download/{fileId}")
    public ResponseEntity<StreamingResponseBody> download(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId,
            @RequestParam(required = false) String ticket,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        FileEntity file = resolveTeamDownload(user, ticket, teamId, fileId, DownloadTicketType.TEAM_FILE);
        if (!"file".equals(file.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND, "Cannot download a folder");
        }
        String mimeType = file.getMimeType() != null ? file.getMimeType() : "application/octet-stream";
        return rangedObjectResponse(file.getStoragePath(), file.getFileSize(), mimeType,
                attachmentDisposition(file.getFileName()), rangeHeader);
    }

    @GetMapping("/{teamId}/download/folders/{fileId}/zip")
    public ResponseEntity<StreamingResponseBody> downloadFolderZip(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId,
            @RequestParam(required = false) String ticket,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        FileEntity folder = resolveTeamDownload(user, ticket, teamId, fileId, DownloadTicketType.TEAM_FOLDER_ZIP);
        TeamTaskEntity task = teamTaskService.startTask(
                teamId,
                user != null ? user.getUserId() : null,
                "FOLDER_ZIP",
                "FILE",
                fileId,
                "Generate team folder ZIP");
        try {
            FolderZipDownloadCacheEntity cache = folderZipDownloadService.getOrCreateCachedZip(folder);
            ResponseEntity<StreamingResponseBody> response = rangedObjectResponse(
                    cache.getObjectKey(),
                    cache.getFileSize(),
                    "application/zip",
                    attachmentDisposition(cache.getFileName()),
                    rangeHeader);
            folderZipDownloadService.markDownloaded(cache);
            teamTaskService.markSucceeded(task.getId(), "ZIP generated: " + cache.getFileName());
            return response;
        } catch (RuntimeException e) {
            teamTaskService.markFailed(task.getId(), e.getMessage());
            throw e;
        }
    }

    @GetMapping("/{teamId}/preview/{fileId}")
    public ResponseEntity<StreamingResponseBody> preview(
            @CurrentUser UserPrincipal user,
            @PathVariable Long teamId,
            @PathVariable Long fileId) {
        teamPermissionService.requireRead(user.getUserId(), teamId, fileId);
        FileEntity file = fileService.getTeamFile(teamId, fileId);
        if (!"file".equals(file.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND, "Cannot preview a folder");
        }
        InputStream stream = storageService.downloadObject(file.getStoragePath());
        String mimeType = file.getMimeType() != null ? file.getMimeType() : "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFileName() + "\"")
                .body(outputStream -> {
                    try (InputStream is = stream) {
                        is.transferTo(outputStream);
                    }
                });
    }

    private ResponseEntity<StreamingResponseBody> thumbnailResponse(FileEntity file) {
        if (!"file".equals(file.getFileType())) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND, "Cannot get thumbnail for a folder");
        }
        if (file.getThumbnailPath() == null || file.getThumbnailPath().isBlank()) {
            throw new BusinessException(ApiCode.FILE_NOT_FOUND, "Thumbnail is not available");
        }

        InputStream stream = storageService.downloadObject(file.getThumbnailPath());
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFileName() + ".jpg\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(outputStream -> {
                    try (InputStream is = stream) {
                        is.transferTo(outputStream);
                    }
                });
    }

    private FileEntity resolveTeamDownload(UserPrincipal user, String ticket, Long teamId, Long fileId, DownloadTicketType type) {
        if (user != null) {
            teamPermissionService.requireRead(user.getUserId(), teamId, fileId);
        } else {
            downloadTicketService.consumeTicket(ticket, fileId, type);
        }
        return fileService.getTeamFile(teamId, fileId);
    }

    private ResponseEntity<StreamingResponseBody> rangedObjectResponse(
            String objectKey,
            long fileSize,
            String mimeType,
            String disposition,
            String rangeHeader) {
        ByteRange range;
        try {
            range = ByteRange.parse(rangeHeader, fileSize);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, ByteRange.unsatisfiedRangeHeader(fileSize))
                    .build();
        }

        if (range == null) {
            InputStream stream = storageService.downloadObject(objectKey);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .contentLength(fileSize)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(outputStream -> {
                        try (InputStream is = stream) {
                            is.transferTo(outputStream);
                        }
                    });
        }

        ByteRange selectedRange = range;
        InputStream stream = storageService.downloadObjectRange(objectKey, selectedRange.start(), selectedRange.length());
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType(mimeType))
                .headers(selectedRange.headers(disposition))
                .body(outputStream -> {
                    try (InputStream is = stream) {
                        is.transferTo(outputStream);
                    }
                });
    }

    private String attachmentDisposition(String fileName) {
        return "attachment; filename=\"" + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "\"";
    }

    private String versionFileName(String fileName, int versionNumber) {
        return "v" + versionNumber + "_" + fileName;
    }

    private boolean canManageAllShares(TeamMemberEntity member, Long fileId) {
        if (TeamService.ROLE_OWNER.equals(member.getRole()) || TeamService.ROLE_ADMIN.equals(member.getRole())) {
            return true;
        }
        return teamPermissionService.effectivePermissions(member.getUserId(), member.getTeamId(), fileId).canManage();
    }

}
