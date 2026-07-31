package com.betterclouddrive.service;

public interface EmailService {
    /** Send 6-digit verification code to user's email */
    default void sendVerificationCode(String toEmail, String code) { sendVerificationCode(toEmail, code, "zh-Hans"); }
    void sendVerificationCode(String toEmail, String code, String locale);

    /** Send password reset code to email */
    default void sendPasswordResetCode(String toEmail, String code) { sendPasswordResetCode(toEmail, code, "zh-Hans"); }
    void sendPasswordResetCode(String toEmail, String code, String locale);

    /** Send share notification to recipient */
    default void sendShareNotification(String toEmail, String shareCode, String fileName, String sharedBy) {
        sendShareNotification(toEmail, shareCode, fileName, sharedBy, "zh-Hans");
    }
    void sendShareNotification(String toEmail, String shareCode, String fileName, String sharedBy, String locale);

    /** Send team invitation email */
    default void sendTeamInvite(String toEmail, String teamName, String role, String inviteLink, java.time.LocalDateTime expiresAt) {
        sendTeamInvite(toEmail, teamName, role, inviteLink, expiresAt, "zh-Hans");
    }
    void sendTeamInvite(String toEmail, String teamName, String role, String inviteLink, java.time.LocalDateTime expiresAt, String locale);

    /** Send a generic notification email */
    default void sendNotification(String toEmail, String eventType, java.util.Map<String, Object> params, String link) {
        sendNotification(toEmail, eventType, params, link, "zh-Hans");
    }
    void sendNotification(String toEmail, String eventType, java.util.Map<String, Object> params, String link, String locale);
}
