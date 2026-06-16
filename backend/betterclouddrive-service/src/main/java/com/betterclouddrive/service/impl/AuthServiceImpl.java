package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.entity.UserTokenEntity;
import com.betterclouddrive.dal.repository.UserRepository;
import com.betterclouddrive.dal.repository.UserTokenRepository;
import com.betterclouddrive.service.AuthService;
import com.betterclouddrive.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final UserTokenRepository userTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final EmailService emailService;

    @Value("${drive.storage.default-quota-bytes:10737418240}")
    private long defaultQuotaBytes;

    private static final String EMAIL_REGISTER_PREFIX = "email:register:";
    private static final String PWD_RESET_PREFIX = "pwd:reset:";
    private static final int CODE_TTL_MINUTES = 10;

    @Override
    @Transactional
    public UserEntity register(String username, String password, String email, String verificationCode) {
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(ApiCode.CONFLICT, "Username already exists");
        }
        String normalizedEmail = normalizeEmail(email);
        if (!StringUtils.hasText(normalizedEmail)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Email is required");
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ApiCode.CONFLICT, "Email already exists");
        }
        String storedCode = redisTemplate.opsForValue().get(EMAIL_REGISTER_PREFIX + normalizedEmail);
        if (storedCode == null) {
            throw new BusinessException(ApiCode.EMAIL_CODE_EXPIRED);
        }
        if (!storedCode.equals(verificationCode)) {
            throw new BusinessException(ApiCode.EMAIL_CODE_MISMATCH);
        }

        UserEntity user = UserEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .email(normalizedEmail)
                .emailVerified(true)
                .webdavEnabled(false)
                .status(1)
                .storageQuota(defaultQuotaBytes)
                .storageUsed(0L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        UserEntity saved = userRepository.save(user);
        redisTemplate.delete(EMAIL_REGISTER_PREFIX + normalizedEmail);
        return saved;
    }

    @Override
    public UserEntity login(String username, String password) {
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.UNAUTHORIZED, "Invalid username or password");
        }
        if (user.getStatus() != 1) {
            throw new BusinessException(ApiCode.UNAUTHORIZED, "Account is disabled");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ApiCode.UNAUTHORIZED, "Invalid username or password");
        }
        return user;
    }

    @Override
    @Transactional
    public void logout(String jti) {
        UserTokenEntity tokenEntity = userTokenRepository.findByJti(jti).orElse(null);
        if (tokenEntity == null) {
            return;
        }

        long expiresAtEpoch = tokenEntity.getExpiresAt()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long remainingMs = expiresAtEpoch - System.currentTimeMillis();
        if (remainingMs > 0) {
            redisTemplate.opsForValue()
                    .set("token:blacklist:" + jti, "1", remainingMs, TimeUnit.MILLISECONDS);
        }

        tokenEntity.setIsRevoked(true);
        userTokenRepository.save(tokenEntity);
    }

    @Override
    public UserEntity getCurrentUser(Long userId) {
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.UNAUTHORIZED);
        }
        return user;
    }

    @Override
    @Transactional
    public UserEntity updateWebDavSettings(Long userId, boolean enabled, String password) {
        UserEntity user = getCurrentUser(userId);
        if (enabled) {
            if (!StringUtils.hasText(password)) {
                throw new BusinessException(ApiCode.BAD_REQUEST, "WebDAV password is required");
            }
            user.setWebdavPasswordHash(passwordEncoder.encode(password));
        }
        user.setWebdavEnabled(enabled);
        return userRepository.save(user);
    }

    @Override
    public void sendRegistrationCode(String email) {
        String normalizedEmail = normalizeEmail(email);
        if (!StringUtils.hasText(normalizedEmail)) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "Email is required");
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ApiCode.CONFLICT, "Email already exists");
        }
        String code = generateCode();
        redisTemplate.opsForValue().set(EMAIL_REGISTER_PREFIX + normalizedEmail, code, CODE_TTL_MINUTES, TimeUnit.MINUTES);
        emailService.sendVerificationCode(normalizedEmail, code);
    }

    @Override
    public void sendPasswordResetCode(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        UserEntity user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            return;
        }
        String code = generateCode();
        redisTemplate.opsForValue().set(PWD_RESET_PREFIX + email, code, CODE_TTL_MINUTES, TimeUnit.MINUTES);
        emailService.sendPasswordResetCode(email, code);
    }

    @Override
    public void verifyPasswordResetCode(String email, String code) {
        String storedCode = redisTemplate.opsForValue().get(PWD_RESET_PREFIX + email);
        if (storedCode == null) {
            throw new BusinessException(ApiCode.EMAIL_CODE_EXPIRED);
        }
        if (!storedCode.equals(code)) {
            throw new BusinessException(ApiCode.EMAIL_CODE_MISMATCH);
        }
    }

    @Override
    @Transactional
    public void resetPassword(String email, String code, String newPassword) {
        String storedCode = redisTemplate.opsForValue().get(PWD_RESET_PREFIX + email);
        if (storedCode == null) {
            throw new BusinessException(ApiCode.EMAIL_CODE_EXPIRED);
        }
        if (!storedCode.equals(code)) {
            throw new BusinessException(ApiCode.EMAIL_CODE_MISMATCH);
        }
        UserEntity user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw new BusinessException(ApiCode.PASSWORD_RESET_FAILED, "User not found");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        redisTemplate.delete(PWD_RESET_PREFIX + email);
    }

    @Override
    public void sendShareNotification(String toEmail, String shareCode, String fileName, String sharedBy) {
        emailService.sendShareNotification(toEmail, shareCode, fileName, sharedBy);
    }

    private String generateCode() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 1000000));
    }

    private String normalizeEmail(String email) {
        return StringUtils.hasText(email) ? email.trim().toLowerCase() : null;
    }
}
