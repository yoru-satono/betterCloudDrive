package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.service.TeamInviteService;
import com.betterclouddrive.web.dto.response.TeamInviteResponse;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/team-invites")
@RequiredArgsConstructor
public class TeamInviteController {

    private final TeamInviteService teamInviteService;

    @GetMapping("/{token}")
    public ApiResponse<TeamInviteResponse> getInvite(@PathVariable String token) {
        return ApiResponse.success(TeamInviteResponse.from(teamInviteService.getInviteByToken(token)));
    }

    @PostMapping("/{token}/accept")
    public ApiResponse<Void> acceptInvite(@CurrentUser UserPrincipal user, @PathVariable String token) {
        teamInviteService.acceptInvite(user.getUserId(), token);
        return ApiResponse.success();
    }

    @PostMapping("/{token}/reject")
    public ApiResponse<Void> rejectInvite(@CurrentUser UserPrincipal user, @PathVariable String token) {
        teamInviteService.rejectInvite(user.getUserId(), token);
        return ApiResponse.success();
    }
}
