package com.betterclouddrive.service.mail;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MailTemplateRendererTest {

    private final MailTemplateRenderer renderer = new MailTemplateRenderer();

    @Test
    void verificationRendersLocalizedMultipartBodies() {
        RenderedEmail english = renderer.render("verification", "en-US", Map.of("code", "123456"));
        RenderedEmail chinese = renderer.render("verification", "zh-CN", Map.of("code", "654321"));

        assertThat(english.subject()).isEqualTo("Verify your BetterCloudDrive email");
        assertThat(english.html()).contains("#f7f8fa", "#007a65", "123456", "BetterCloudDrive");
        assertThat(english.plainText()).contains("Verify your email", "123456");
        assertThat(chinese.subject()).isEqualTo("BetterCloudDrive 邮箱验证");
        assertThat(chinese.html()).contains("验证你的邮箱", "654321");
    }

    @Test
    void userControlledValuesAreEscapedInHtmlAndRemainReadableInText() {
        RenderedEmail email = renderer.render("share", "en-US", Map.of(
                "sharedBy", "<script>alert(1)</script>",
                "fileName", "Q&A <draft>.txt",
                "shareCode", "ABC123",
                "link", "https://cloud.example/s/ABC123",
                "details", Map.of("fileName", "Q&A <draft>.txt", "shareCode", "ABC123")));

        assertThat(email.html()).doesNotContain("<script>alert(1)</script>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;", "Q&amp;A &lt;draft&gt;.txt");
        assertThat(email.plainText()).contains("Q&A <draft>.txt", "https://cloud.example/s/ABC123");
    }

    @Test
    void allNotificationTypesHaveEnglishAndChineseContent() {
        String[] types = {
                "TEAM_INVITE", "TEAM_INVITE_ACCEPT", "TEAM_INVITE_REJECT", "TEAM_QUOTA_ALERT",
                "TEAM_ARCHIVE", "TEAM_UNARCHIVE", "TEAM_RESTORE", "TEAM_PERMISSION_UPDATE",
                "TEAM_DELETE", "TEAM_MEMBER_ADD", "TEAM_ROLE_UPDATE", "TEAM_MEMBER_REMOVE",
                "TEAM_OWNER_TRANSFER", "TEAM_OFFICE_SAVE_FAILED"
        };
        Map<String, Object> values = Map.of("teamName", "Product", "userName", "Alice", "threshold", 90);
        for (String type : types) {
            String key = renderer.notificationKey(type);
            assertThat(renderer.render(key, "en-US", values).subject()).isNotBlank();
            assertThat(renderer.render(key, "zh-CN", values).subject()).isNotBlank();
        }
        assertThat(renderer.notificationKey("UNEXPECTED_EVENT")).isEqualTo("notification.generic");
    }

    @Test
    void passwordResetAndTeamInviteTemplatesRenderAllDynamicFields() {
        RenderedEmail reset = renderer.render("password_reset", "zh-CN", Map.of("code", "112233"));
        RenderedEmail invite = renderer.render("team_invite", "en-US", Map.of(
                "teamName", "Product", "role", "ROLE_EDITOR", "expiresAt", "2026-07-20 12:00",
                "link", "https://cloud.example/team-invites/token",
                "details", Map.of("teamName", "Product", "role", "ROLE_EDITOR", "expiresAt", "2026-07-20 12:00")));

        assertThat(reset.html()).contains("112233", "重置你的密码");
        assertThat(invite.subject()).isEqualTo("Invitation to join Product");
        assertThat(invite.html()).contains("Editor", "2026-07-20 12:00", "https://cloud.example/team-invites/token")
                .doesNotContain("ROLE_EDITOR");
    }
}
