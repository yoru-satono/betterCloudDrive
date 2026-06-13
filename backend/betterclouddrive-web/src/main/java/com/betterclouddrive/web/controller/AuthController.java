package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.dto.ApiResponse;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.entity.UserTokenEntity;
import com.betterclouddrive.dal.repository.UserTokenRepository;
import com.betterclouddrive.service.AuthService;
import com.betterclouddrive.web.dto.request.ForgotPasswordRequest;
import com.betterclouddrive.web.dto.request.LoginRequest;
import com.betterclouddrive.web.dto.request.RegisterCodeRequest;
import com.betterclouddrive.web.dto.request.RefreshTokenRequest;
import com.betterclouddrive.web.dto.request.RegisterRequest;
import com.betterclouddrive.web.dto.request.ResetPasswordRequest;
import com.betterclouddrive.web.security.CurrentUser;
import com.betterclouddrive.web.security.JwtTokenProvider;
import com.betterclouddrive.web.security.UserPrincipal;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserTokenRepository userTokenRepository;
    private final StringRedisTemplate redisTemplate;

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        UserEntity user = authService.register(
                request.getUsername(), request.getPassword(), request.getEmail(), request.getVerificationCode());
        return ApiResponse.success(Map.of("userId", user.getId(), "username", user.getUsername()));
    }

    @PostMapping("/register-code/send")
    public ApiResponse<Void> sendRegisterCode(@Valid @RequestBody RegisterCodeRequest request) {
        authService.sendRegistrationCode(request.getEmail());
        return ApiResponse.success();
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        UserEntity user = authService.login(request.getUsername(), request.getPassword());
        Long userId = user.getId();
        String role = user.getRole() != null ? user.getRole() : "ROLE_USER";

        String accessToken = jwtTokenProvider.createAccessToken(userId, request.getUsername(), role);
        String refreshToken = jwtTokenProvider.createRefreshToken(userId);

        Claims accessClaims = jwtTokenProvider.validateAccessToken(accessToken);
        Claims refreshClaims = jwtTokenProvider.validateRefreshToken(refreshToken);

        UserTokenEntity accessTokenEntity = UserTokenEntity.builder()
                .userId(userId).jti(accessClaims.getId()).tokenType("access")
                .issuedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.ofInstant(accessClaims.getExpiration().toInstant(), ZoneId.systemDefault()))
                .build();
        userTokenRepository.save(accessTokenEntity);

        UserTokenEntity refreshTokenEntity = UserTokenEntity.builder()
                .userId(userId).jti(refreshClaims.getId()).tokenType("refresh")
                .issuedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.ofInstant(refreshClaims.getExpiration().toInstant(), ZoneId.systemDefault()))
                .build();
        userTokenRepository.save(refreshTokenEntity);

        return ApiResponse.success(Map.of(
                "accessToken", accessToken,
                "refreshToken", refreshToken,
                "expiresIn", jwtTokenProvider.getAccessExpirationMs() / 1000
        ));
    }

    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        Claims claims = jwtTokenProvider.validateRefreshToken(request.getRefreshToken());
        String jti = claims.getId();
        Long userId = Long.parseLong(claims.getSubject());

        UserTokenEntity oldToken = userTokenRepository.findByJti(jti).orElse(null);
        if (oldToken != null) {
            long remainingMs = oldToken.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    - System.currentTimeMillis();
            if (remainingMs > 0) {
                redisTemplate.opsForValue().set("token:blacklist:" + jti, "1", remainingMs, TimeUnit.MILLISECONDS);
            }
            oldToken.setIsRevoked(true);
            userTokenRepository.save(oldToken);
        }

        UserEntity currentUser = authService.getCurrentUser(userId);
        String currentRole = currentUser.getRole() != null ? currentUser.getRole() : "ROLE_USER";

        String newAccessToken = jwtTokenProvider.createAccessToken(userId,
                claims.get("username", String.class), currentRole);
        String newRefreshToken = jwtTokenProvider.createRefreshToken(userId);

        return ApiResponse.success(Map.of(
                "accessToken", newAccessToken,
                "refreshToken", newRefreshToken,
                "expiresIn", jwtTokenProvider.getAccessExpirationMs() / 1000
        ));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@CurrentUser UserPrincipal user) {
        authService.logout(user.getJti());
        return ApiResponse.success();
    }

    @GetMapping("/me")
    public ApiResponse<UserEntity> me(@CurrentUser UserPrincipal user) {
        return ApiResponse.success(authService.getCurrentUser(user.getUserId()));
    }

    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.sendPasswordResetCode(request.getEmail());
        return ApiResponse.success();
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getEmail(), request.getCode(), request.getNewPassword());
        return ApiResponse.success();
    }
}
