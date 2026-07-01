package com.betterclouddrive.service.impl;

import com.betterclouddrive.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    private static final String FROM = "noreply@betterclouddrive.local";
    private static final String APP_NAME = "BetterCloudDrive";

    @Override
    @Async("emailExecutor")
    public void sendVerificationCode(String toEmail, String code) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Cannot send verification code: email is empty");
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(FROM);
        msg.setTo(toEmail);
        msg.setSubject(APP_NAME + " - Email Verification");
        msg.setText("Your verification code: " + code + "\n\n"
                + "This code is valid for 10 minutes.\n\n"
                + "If you did not request this, please ignore this email.");
        mailSender.send(msg);
        log.info("Verification code sent to: {}", toEmail);
    }

    @Override
    @Async("emailExecutor")
    public void sendPasswordResetCode(String toEmail, String code) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Cannot send password reset code: email is empty");
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(FROM);
        msg.setTo(toEmail);
        msg.setSubject(APP_NAME + " - Password Reset");
        msg.setText("Your password reset code: " + code + "\n\n"
                + "This code is valid for 10 minutes.\n\n"
                + "If you did not request a password reset, please ignore this email.");
        mailSender.send(msg);
        log.info("Password reset code sent to: {}", toEmail);
    }

    @Override
    @Async("emailExecutor")
    public void sendShareNotification(String toEmail, String shareCode, String fileName, String sharedBy) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Cannot send share notification: email is empty");
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(FROM);
        msg.setTo(toEmail);
        msg.setSubject(APP_NAME + " - File Shared With You");
        msg.setText(sharedBy + " shared a file with you on " + APP_NAME + ".\n\n"
                + "File: " + fileName + "\n"
                + "Share Code: " + shareCode + "\n\n"
                + "Use this code to access the shared file.");
        mailSender.send(msg);
        log.info("Share notification sent to: {} for file: {}", toEmail, fileName);
    }

    @Override
    @Async("emailExecutor")
    public void sendTeamInvite(String toEmail, String teamName, String role, String inviteLink, LocalDateTime expiresAt) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Cannot send team invite: email is empty");
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(FROM);
        msg.setTo(toEmail);
        msg.setSubject(APP_NAME + " - Team Invitation");
        msg.setText("You have been invited to join team \"" + teamName + "\" as " + role + ".\n\n"
                + "Open this link to accept the invitation:\n" + inviteLink + "\n\n"
                + "This invitation expires at " + expiresAt + ".\n\n"
                + "If you did not expect this invitation, you can ignore this email.");
        mailSender.send(msg);
        log.info("Team invite sent to: {} for team: {}", toEmail, teamName);
    }

    @Override
    @Async("emailExecutor")
    public void sendNotification(String toEmail, String title, String content, String link) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Cannot send notification email: email is empty");
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(FROM);
        msg.setTo(toEmail);
        msg.setSubject(APP_NAME + " - " + title);
        msg.setText((content != null ? content : "") + (link != null && !link.isBlank() ? "\n\n" + link : ""));
        mailSender.send(msg);
        log.info("Notification email sent to: {}", toEmail);
    }
}
