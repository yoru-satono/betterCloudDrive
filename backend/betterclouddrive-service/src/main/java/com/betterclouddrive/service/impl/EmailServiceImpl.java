package com.betterclouddrive.service.impl;

import com.betterclouddrive.service.EmailService;
import com.betterclouddrive.service.mail.MailTemplateRenderer;
import com.betterclouddrive.service.mail.RenderedEmail;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final JavaMailSender mailSender;
    private final MailTemplateRenderer renderer;

    @Value("${drive.mail.from:noreply@betterclouddrive.local}")
    private String from;

    @Value("${drive.app-public-url:http://localhost:3000}")
    private String appPublicUrl;

    @Override
    @Async("emailExecutor")
    public void sendVerificationCode(String toEmail, String code, String locale) {
        send(toEmail, renderer.render("verification", locale, Map.of("code", code)), "verification code");
    }

    @Override
    @Async("emailExecutor")
    public void sendPasswordResetCode(String toEmail, String code, String locale) {
        send(toEmail, renderer.render("password_reset", locale, Map.of("code", code)), "password reset code");
    }

    @Override
    @Async("emailExecutor")
    public void sendShareNotification(String toEmail, String shareCode, String fileName, String sharedBy, String locale) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sharedBy", safe(sharedBy));
        values.put("fileName", safe(fileName));
        values.put("shareCode", safe(shareCode));
        values.put("link", publicUrl("/s/" + safePath(shareCode)));
        values.put("details", Map.of("fileName", safe(fileName), "shareCode", safe(shareCode)));
        send(toEmail, renderer.render("share", locale, values), "share notification");
    }

    @Override
    @Async("emailExecutor")
    public void sendTeamInvite(String toEmail, String teamName, String role, String inviteLink,
                               LocalDateTime expiresAt, String locale) {
        String expiry = expiresAt == null ? "-" : EXPIRY_FORMAT.format(expiresAt);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("teamName", safe(teamName));
        values.put("role", safe(role));
        values.put("expiresAt", expiry);
        values.put("link", absoluteUrl(inviteLink));
        values.put("details", Map.of("teamName", safe(teamName), "role", safe(role), "expiresAt", expiry));
        send(toEmail, renderer.render("team_invite", locale, values), "team invite");
    }

    @Override
    @Async("emailExecutor")
    public void sendNotification(String toEmail, String eventType, Map<String, Object> params,
                                 String link, String locale) {
        Map<String, Object> values = sanitizedParams(params);
        values.put("link", absoluteUrl(link));
        send(toEmail, renderer.render(renderer.notificationKey(eventType), locale, values), "notification");
    }

    private void send(String toEmail, RenderedEmail email, String kind) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Cannot send {}: email is empty", kind);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(toEmail.trim());
            helper.setSubject(email.subject());
            helper.setText(email.plainText(), email.html());
            mailSender.send(message);
            log.info("{} sent to: {}", kind, toEmail);
        } catch (MessagingException exception) {
            throw new IllegalStateException("Failed to create " + kind + " email", exception);
        }
    }

    private Map<String, Object> sanitizedParams(Map<String, Object> params) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (params != null) params.forEach((key, value) -> {
            String safeValue = safe(value);
            values.put(key, safeValue);
        });
        values.put("details", Map.of());
        return values;
    }

    private String absoluteUrl(String link) {
        if (link == null || link.isBlank()) return "";
        if (link.startsWith("https://") || link.startsWith("http://")) return link;
        return publicUrl(link.startsWith("/") ? link : "/" + link);
    }

    private String publicUrl(String path) {
        return appPublicUrl.replaceAll("/+$", "") + path;
    }

    private String safePath(Object value) {
        return String.valueOf(value == null ? "" : value).replaceAll("[^A-Za-z0-9_-]", "");
    }

    private String safe(Object value) {
        return String.valueOf(value == null ? "" : value).replace('\r', ' ').replace('\n', ' ');
    }
}
