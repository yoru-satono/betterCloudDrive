package com.betterclouddrive.web.warehouse;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.common.dto.WarehouseEvent.Type;
import com.betterclouddrive.service.WarehouseEventService;
import com.betterclouddrive.web.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Count successful user operations, excluding hydration/polling and all admin APIs. */
@ControllerAdvice
@RequiredArgsConstructor
public class WarehouseActivityAdvice implements ResponseBodyAdvice<Object> {
    private static final Set<String> CONTROLLERS = Set.of("FileController", "FavoriteController", "TagController",
            "RecycleBinController", "FileVersionController", "ShareController", "TeamController");
    private static final Set<String> EXCLUDED = Set.of("listTasks", "listActivities", "listAuditLogs",
            "effectivePermission", "listPermissions", "isTeamFavorite", "getTeam", "getSharePassword",
            "getTeamSharePassword", "getTeamShares", "getTeamFileTags", "listTeamFileTags", "getUploadStatus");
    private final WarehouseEventService events;

    @Override
    public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return CONTROLLERS.contains(method.getContainingClass().getSimpleName())
                && method.getMethod() != null && !EXCLUDED.contains(method.getMethod().getName());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter method, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(body instanceof ApiResponse<?> envelope) || envelope.getCode() != 200
                || authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) return body;
        Long teamId = null;
        if (request instanceof ServletServerHttpRequest servletRequest) {
            Object attribute = servletRequest.getServletRequest().getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (attribute instanceof Map<?, ?> variables && variables.get("teamId") != null) {
                teamId = Long.valueOf(variables.get("teamId").toString());
            }
        }
        if (teamId == null && envelope.getData() instanceof com.betterclouddrive.web.dto.response.TeamResponse team
                && method.getMethod() != null && "createTeam".equals(method.getMethod().getName())) {
            teamId = team.getId();
        }
        events.record(Type.USER_ACTIVITY, principal.getUserId(), teamId, null, "activity:" + UUID.randomUUID());
        return body;
    }
}
