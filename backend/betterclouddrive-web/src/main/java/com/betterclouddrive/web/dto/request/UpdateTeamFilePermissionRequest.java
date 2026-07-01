package com.betterclouddrive.web.dto.request;

import lombok.Data;

@Data
public class UpdateTeamFilePermissionRequest {
    private String subjectType;
    private Long subjectUserId;
    private String subjectRole;
    private Boolean canRead;
    private Boolean canWrite;
    private Boolean canShare;
    private Boolean canDelete;
    private Boolean canManage;
}
