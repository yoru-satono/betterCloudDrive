package com.betterclouddrive.service.dto;

import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.entity.TeamInviteEntity;
import com.betterclouddrive.dal.entity.UserEntity;

public record TeamInviteView(TeamInviteEntity invite, TeamEntity team, UserEntity invitedBy) {
}
