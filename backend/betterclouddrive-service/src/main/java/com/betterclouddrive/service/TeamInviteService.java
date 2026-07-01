package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.TeamInviteEntity;
import com.betterclouddrive.service.dto.TeamInviteView;

import java.util.List;

public interface TeamInviteService {

    TeamInviteView createInvite(Long actorUserId, Long teamId, String email, String role);

    List<TeamInviteView> listInvites(Long actorUserId, Long teamId);

    TeamInviteView resendInvite(Long actorUserId, Long teamId, Long inviteId);

    void cancelInvite(Long actorUserId, Long teamId, Long inviteId);

    TeamInviteView getInviteByToken(String token);

    TeamInviteEntity acceptInvite(Long userId, String token);

    TeamInviteEntity rejectInvite(Long userId, String token);
}
