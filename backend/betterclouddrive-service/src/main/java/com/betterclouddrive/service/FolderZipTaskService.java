package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.service.dto.TeamTaskDownload;

public interface FolderZipTaskService {

    TeamTaskEntity queueTeamFolderZip(Long requesterUserId, Long teamId, Long fileId);

    int processPendingFolderZips(int batchSize);

    TeamTaskDownload downloadTeamFolderZipResult(Long requesterUserId, Long teamId, Long taskId);
}
