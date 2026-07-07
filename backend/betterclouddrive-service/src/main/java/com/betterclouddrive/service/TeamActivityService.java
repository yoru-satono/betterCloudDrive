package com.betterclouddrive.service;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.service.dto.TeamActivityItem;

public interface TeamActivityService {

    PageResult<TeamActivityItem> listActivities(Long userId, Long teamId, int page, int size);
}
