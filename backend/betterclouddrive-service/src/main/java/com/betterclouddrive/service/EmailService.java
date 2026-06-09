package com.betterclouddrive.service;

public interface EmailService {
    /** Send 6-digit verification code to user's email */
    void sendVerificationCode(String toEmail, String code);

    /** Send password reset code to email */
    void sendPasswordResetCode(String toEmail, String code);

    /** Send share notification to recipient */
    void sendShareNotification(String toEmail, String shareCode, String fileName, String sharedBy);
}
