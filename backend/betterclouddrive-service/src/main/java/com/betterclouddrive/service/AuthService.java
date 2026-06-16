package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.UserEntity;

public interface AuthService {
    UserEntity register(String username, String password, String email, String verificationCode);
    UserEntity login(String username, String password);
    void logout(String jti);
    UserEntity getCurrentUser(Long userId);
    UserEntity updateWebDavSettings(Long userId, boolean enabled, String password);

    /** Send 6-digit verification code before account registration */
    void sendRegistrationCode(String email);

    /** Send password reset code to email. Silently succeeds even if email not found. */
    void sendPasswordResetCode(String email);

    /** Verify password reset code */
    void verifyPasswordResetCode(String email, String code);

    /** Reset password after code verification */
    void resetPassword(String email, String code, String newPassword);

    /** Send share notification email */
    void sendShareNotification(String toEmail, String shareCode, String fileName, String sharedBy);
}
