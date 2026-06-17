package com.betterclouddrive.web.interceptor;

import com.betterclouddrive.service.OperationLogService;
import com.betterclouddrive.web.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

@Slf4j
@Aspect
@Component
@ConditionalOnProperty(name = "observability.legacy-operation-aspect.enabled", havingValue = "true")
@RequiredArgsConstructor
public class OperationLogAspect {

    private final OperationLogService operationLogService;

    @AfterReturning("@annotation(org.springframework.web.bind.annotation.PostMapping) || " +
                    "@annotation(org.springframework.web.bind.annotation.PutMapping) || " +
                    "@annotation(org.springframework.web.bind.annotation.DeleteMapping)")
    public void logWriteOperations(JoinPoint joinPoint) {
        try {
            String methodName = joinPoint.getSignature().getName();
            String className = joinPoint.getSignature().getDeclaringType().getSimpleName();

            // Determine action type from method name
            String action = mapAction(methodName, className);

            Long userId = getCurrentUserId().orElse(null);
            if (userId == null) return;

            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            String ip = attrs != null ? attrs.getRequest().getRemoteAddr() : null;
            String ua = attrs != null ? attrs.getRequest().getHeader("User-Agent") : null;

            operationLogService.logAsync(userId, action, "FILE", null, className + "." + methodName, ip, ua);
        } catch (Exception e) {
            // Log failure should not affect business
        }
    }

    private String mapAction(String methodName, String className) {
        String name = methodName.toLowerCase();
        if (name.contains("upload") || name.contains("create")) return "UPLOAD";
        if (name.contains("download")) return "DOWNLOAD";
        if (name.contains("delete") || name.contains("cancel") || name.contains("empty")) return "DELETE";
        if (name.contains("move")) return "MOVE";
        if (name.contains("copy")) return "COPY";
        if (name.contains("rename")) return "RENAME";
        if (name.contains("restore")) return "RESTORE";
        if (name.contains("share")) return "SHARE";
        if (name.contains("login")) return "LOGIN";
        if (name.contains("logout")) return "LOGOUT";
        if (name.contains("register")) return "REGISTER";
        return "OTHER";
    }

    private Optional<Long> getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal) {
            return Optional.of(((UserPrincipal) auth.getPrincipal()).getUserId());
        }
        return Optional.empty();
    }
}
