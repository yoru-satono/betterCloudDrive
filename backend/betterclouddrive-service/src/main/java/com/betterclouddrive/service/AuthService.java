package com.betterclouddrive.service;

import com.betterclouddrive.dal.entity.UserEntity;

public interface AuthService {
    UserEntity register(String username, String password, String email);
    UserEntity login(String username, String password);
    void logout(String jti);
    UserEntity getCurrentUser(Long userId);

    /** Send 6-digit email verification code to the user's registered email */
    void sendVerificationCode(Long userId);

    /** Confirm verification code, mark email as verified */
    void verifyEmail(Long userId, String code);

    /** Send password reset code to email. Silently succeeds even if email not found. */
    void sendPasswordResetCode(String email);

    /** Verify password reset code */
    void verifyPasswordResetCode(String email, String code);

    /** Reset password after code verification */
    void resetPassword(String email, String code, String newPassword);

    /** Send share notification email */
    void sendShareNotification(String toEmail, String shareCode, String fileName, String sharedBy);
}
