package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.dal.entity.UserTokenEntity;
import com.betterclouddrive.dal.mapper.UserMapper;
import com.betterclouddrive.dal.mapper.UserTokenMapper;
import com.betterclouddrive.service.AuthService;
import com.betterclouddrive.service.EmailService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final UserTokenMapper userTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final EmailService emailService;

    @Value("${drive.storage.default-quota-bytes:10737418240}")
    private long defaultQuotaBytes;

    private static final String EMAIL_VERIFY_PREFIX = "email:verify:";
    private static final String PWD_RESET_PREFIX = "pwd:reset:";
    private static final int CODE_TTL_MINUTES = 10;

    @Override
    @Transactional
    public UserEntity register(String username, String password, String email) {
        // Check username uniqueness
        if (userMapper.selectCount(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username)) > 0) {
            throw new BusinessException(ApiCode.CONFLICT, "Username already exists");
        }
        if (email != null && userMapper.selectCount(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, email)) > 0) {
            throw new BusinessException(ApiCode.CONFLICT, "Email already exists");
        }

        UserEntity user = UserEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .email(email)
                .status(1)
                .storageQuota(defaultQuotaBytes)
                .storageUsed(0L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        userMapper.insert(user);
        return user;
    }

    @Override
    public UserEntity login(String username, String password) {
        UserEntity user = userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
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
        UserTokenEntity tokenEntity = userTokenMapper.selectByJti(jti);
        if (tokenEntity == null) {
            return;
        }

        // Calculate remaining validity and add to Redis blacklist
        long expiresAtEpoch = tokenEntity.getExpiresAt()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long remainingMs = expiresAtEpoch - System.currentTimeMillis();
        if (remainingMs > 0) {
            redisTemplate.opsForValue()
                    .set("token:blacklist:" + jti, "1", remainingMs, TimeUnit.MILLISECONDS);
        }

        // Revoke token in database
        tokenEntity.setIsRevoked(true);
        userTokenMapper.updateById(tokenEntity);
    }

    @Override
    public UserEntity getCurrentUser(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ApiCode.UNAUTHORIZED);
        }
        return user;
    }

    @Override
    public void sendVerificationCode(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw new BusinessException(ApiCode.EMAIL_VERIFICATION_FAILED, "No email address on file");
        }
        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new BusinessException(ApiCode.EMAIL_VERIFICATION_FAILED, "Email is already verified");
        }
        String code = generateCode();
        redisTemplate.opsForValue().set(EMAIL_VERIFY_PREFIX + userId, code, CODE_TTL_MINUTES, TimeUnit.MINUTES);
        emailService.sendVerificationCode(user.getEmail(), code);
    }

    @Override
    @Transactional
    public void verifyEmail(Long userId, String code) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ApiCode.EMAIL_VERIFICATION_FAILED, "User not found");
        }
        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            return; // Already verified, no-op
        }
        String storedCode = redisTemplate.opsForValue().get(EMAIL_VERIFY_PREFIX + userId);
        if (storedCode == null) {
            throw new BusinessException(ApiCode.EMAIL_CODE_EXPIRED);
        }
        if (!storedCode.equals(code)) {
            throw new BusinessException(ApiCode.EMAIL_CODE_MISMATCH);
        }
        user.setEmailVerified(true);
        userMapper.updateById(user);
        redisTemplate.delete(EMAIL_VERIFY_PREFIX + userId);
    }

    @Override
    public void sendPasswordResetCode(String email) {
        if (email == null || email.isBlank()) {
            return; // Silent — don't reveal whether email exists
        }
        UserEntity user = userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, email));
        if (user == null) {
            return; // Silent — user not found
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
        UserEntity user = userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, email));
        if (user == null) {
            throw new BusinessException(ApiCode.PASSWORD_RESET_FAILED, "User not found");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        redisTemplate.delete(PWD_RESET_PREFIX + email);
    }

    @Override
    public void sendShareNotification(String toEmail, String shareCode, String fileName, String sharedBy) {
        emailService.sendShareNotification(toEmail, shareCode, fileName, sharedBy);
    }

    private String generateCode() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 1000000));
    }
}
