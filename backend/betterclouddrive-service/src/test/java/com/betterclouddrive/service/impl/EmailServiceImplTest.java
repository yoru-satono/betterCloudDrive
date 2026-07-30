package com.betterclouddrive.service.impl;

import com.betterclouddrive.service.mail.MailTemplateRenderer;
import com.betterclouddrive.service.i18n.LocaleCatalog;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.lang.reflect.Field;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailServiceImplTest {

    private JavaMailSender mailSender;
    private EmailServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        mailSender = mock(JavaMailSender.class);
        service = new EmailServiceImpl(mailSender, new MailTemplateRenderer(new LocaleCatalog("zh-CN")));
        setField("from", "noreply@test.local");
        setField("appPublicUrl", "https://cloud.example/");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void sendsHtmlAndPlainTextAsMultipartAlternative() throws Exception {
        service.sendVerificationCode("user@example.com", "123456", "en-US");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("Verify your BetterCloudDrive email");
        assertThat(message.getFrom()[0].toString()).contains("noreply@test.local");
        assertThat(message.getContentType()).containsIgnoringCase("multipart/mixed");
        assertThat(message.getContent().toString()).isNotBlank();
    }

    @Test
    void shareEmailContainsAbsolutePublicLink() throws Exception {
        service.sendShareNotification("friend@example.com", "ABC123", "report.pdf", "Alice", "en-US");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        captor.getValue().saveChanges();
        assertThat(captor.getValue().getContent().toString()).contains("MimeMultipart");
        assertThat(captor.getValue().getSubject()).isEqualTo("Alice shared a file with you");
    }

    private void setField(String name, String value) throws Exception {
        Field field = EmailServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
