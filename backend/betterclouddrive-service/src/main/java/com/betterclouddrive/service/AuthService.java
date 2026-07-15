package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.UserEntity;

public interface AuthService {
    default UserEntity register(String username, String password, String email, String verificationCode) {
        return register(username, password, email, verificationCode, "zh-CN");
    }
    UserEntity register(String username, String password, String email, String verificationCode, String preferredLocale);
    UserEntity login(String username, String password);
    void logout(String jti);
    UserEntity getCurrentUser(Long userId);
    UserEntity updateWebDavSettings(Long userId, boolean enabled, String password);
    UserEntity updatePreferredLocale(Long userId, String preferredLocale);

    /** Send 6-digit verification code before account registration */
    default void sendRegistrationCode(String email) { sendRegistrationCode(email, "zh-CN"); }
    void sendRegistrationCode(String email, String preferredLocale);

    /** Send password reset code to email. Silently succeeds even if email not found. */
    void sendPasswordResetCode(String email);

    /** Verify password reset code */
    void verifyPasswordResetCode(String email, String code);

    /** Reset password after code verification */
    void resetPassword(String email, String code, String newPassword);

    /** Send share notification email */
    void sendShareNotification(String toEmail, String shareCode, String fileName, String sharedBy);
    void sendShareNotification(Long actorUserId, String toEmail, String shareCode, String fileName, String sharedBy);
}
