package com.betterclouddrive.web.service;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.web.config.OnlyOfficeProperties;
import com.betterclouddrive.web.dto.response.OfficeConfigResponse;
import com.betterclouddrive.service.i18n.LocaleCatalog;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class OnlyOfficeServiceTest {

    private static final String ONLYOFFICE_AI_PLUGIN_GUID = "asc.{9DC93CDB-B576-4F0C-B55E-FCC9C48DD007}";

    private final OnlyOfficeProperties properties = new OnlyOfficeProperties();
    private final OnlyOfficeService service =
            new OnlyOfficeService(properties, RestClient.builder(), new LocaleCatalog("zh-Hans"));

    OnlyOfficeServiceTest() {
        properties.setJwtSecret("01234567890123456789012345678901");
        properties.setPublicUrl("http://localhost:8082/");
        properties.setAppInternalApiBaseUrl("http://backend:8080/api/v1/");
    }

    @Test
    void buildPersonalConfig_shouldUseRequestedEditorLocale() {
        OfficeConfigResponse response = service.buildPersonalConfig(1L, officeFile("document.docx"), "en-US");
        @SuppressWarnings("unchecked")
        Map<String, Object> editorConfig = (Map<String, Object>) response.getConfig().get("editorConfig");
        assertThat(editorConfig.get("lang")).isEqualTo("en-US");
    }

    @ParameterizedTest
    @CsvSource({
            "report.docx,word",
            "sheet.xlsx,cell",
            "slides.pptx,slide"
    })
    void buildPersonalConfig_shouldCreateReadOnlyConfigWithoutCallback(String fileName, String documentType) {
        FileEntity file = officeFile(fileName);

        OfficeConfigResponse response = service.buildPersonalConfig(1L, file);

        assertThat(response.getDocumentServerUrl()).isEqualTo("http://localhost:8082");
        assertThat(response.isEditable()).isFalse();
        assertThat(response.getConfig()).containsEntry("documentType", documentType);
        assertThat(response.getConfig()).containsKey("token");
        @SuppressWarnings("unchecked")
        Map<String, Object> editorConfig = (Map<String, Object>) response.getConfig().get("editorConfig");
        assertThat(editorConfig).containsEntry("mode", "view");
        assertThat(editorConfig).doesNotContainKey("callbackUrl");
        @SuppressWarnings("unchecked")
        Map<String, Object> customization = (Map<String, Object>) editorConfig.get("customization");
        assertThat(customization)
                .containsEntry("plugins", false)
                .containsEntry("macros", false)
                .containsEntry("macrosMode", "disable");
        assertPluginsDisabled(editorConfig);
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) response.getConfig().get("document");
        assertThat((String) document.get("url")).startsWith("http://backend:8080/api/v1/office/files/7/content?token=");
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) document.get("permissions");
        assertThat(permissions)
                .containsEntry("download", true)
                .containsEntry("print", true)
                .containsEntry("edit", false)
                .containsEntry("review", false)
                .containsEntry("comment", false);
    }

    @Test
    void validateContentToken_shouldReturnUserId() {
        OfficeConfigResponse response = service.buildTeamConfig(2L, 10L, officeFile("sheet.xlsx"), true);
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) response.getConfig().get("document");
        String url = (String) document.get("url");
        String token = url.substring(url.indexOf("token=") + 6);

        Long userId = service.validateContentToken(token, 7L, 10L);

        assertThat(userId).isEqualTo(2L);
    }

    @Test
    void buildTeamConfig_shouldExposeEditableCallbackAndVersionedDocumentKey() {
        FileEntity file = officeFile("sheet.xlsx");

        OfficeConfigResponse first = service.buildTeamConfig(2L, 10L, file, true);
        file.setVersionCount(2);
        file.setMd5Hash("md5-v2");
        OfficeConfigResponse second = service.buildTeamConfig(2L, 10L, file, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> firstEditorConfig = (Map<String, Object>) first.getConfig().get("editorConfig");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstDocument = (Map<String, Object>) first.getConfig().get("document");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondDocument = (Map<String, Object>) second.getConfig().get("document");

        assertThat(first.isEditable()).isTrue();
        assertThat(firstEditorConfig).containsEntry("mode", "edit");
        assertPluginsDisabled(firstEditorConfig);
        assertThat((String) firstEditorConfig.get("callbackUrl"))
                .startsWith("http://backend:8080/api/v1/teams/10/office/files/7/callback?token=");
        assertThat(firstDocument.get("key")).isNotEqualTo(secondDocument.get("key"));
    }

    @Test
    void buildTeamConfig_shouldForceSignedMobileConfigToReadOnly() {
        OfficeConfigResponse response = service.buildTeamConfig(
                2L, 10L, officeFile("document.docx"), true, "zh-Hans", true);

        assertThat(response.isEditable()).isFalse();
        assertThat(response.getConfig()).containsEntry("type", "mobile");

        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) response.getConfig().get("document");
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) document.get("permissions");
        assertThat(permissions)
                .containsEntry("download", true)
                .containsEntry("print", true)
                .containsEntry("edit", false)
                .containsEntry("review", false)
                .containsEntry("comment", false)
                .containsEntry("fillForms", false);

        @SuppressWarnings("unchecked")
        Map<String, Object> editorConfig = (Map<String, Object>) response.getConfig().get("editorConfig");
        assertThat(editorConfig).containsEntry("mode", "view").doesNotContainKey("callbackUrl");
        @SuppressWarnings("unchecked")
        Map<String, Object> customization = (Map<String, Object>) editorConfig.get("customization");
        @SuppressWarnings("unchecked")
        Map<String, Object> mobile = (Map<String, Object>) customization.get("mobile");
        assertThat(mobile)
                .containsEntry("forceView", true)
                .containsEntry("disableForceDesktop", true);

        Claims signedConfig = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims((String) response.getConfig().get("token"))
                .getPayload();
        assertThat(signedConfig.get("type")).isEqualTo("mobile");
        @SuppressWarnings("unchecked")
        Map<String, Object> signedEditorConfig = signedConfig.get("editorConfig", Map.class);
        assertThat(signedEditorConfig).containsEntry("mode", "view").doesNotContainKey("callbackUrl");
        @SuppressWarnings("unchecked")
        Map<String, Object> signedDocument = signedConfig.get("document", Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> signedPermissions = (Map<String, Object>) signedDocument.get("permissions");
        assertThat(signedPermissions)
                .containsEntry("edit", false)
                .containsEntry("review", false)
                .containsEntry("comment", false)
                .containsEntry("fillForms", false);
    }

    @Test
    void validateCallbackToken_shouldRequireMatchingDocumentKey() {
        OfficeConfigResponse response = service.buildTeamConfig(2L, 10L, officeFile("sheet.xlsx"), true);
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) response.getConfig().get("document");
        @SuppressWarnings("unchecked")
        Map<String, Object> editorConfig = (Map<String, Object>) response.getConfig().get("editorConfig");
        String callbackUrl = (String) editorConfig.get("callbackUrl");
        String token = callbackUrl.substring(callbackUrl.indexOf("token=") + 6);

        Long userId = service.validateCallbackToken(token, 7L, 10L, (String) document.get("key"));

        assertThat(userId).isEqualTo(2L);
        assertThatThrownBy(() -> service.validateCallbackToken(token, 7L, 10L, "wrong-key"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void resolveDocumentServerDownloadUrl_shouldUseInternalOriginForCallbackDownloads() {
        properties.setInternalUrl("http://onlyoffice-documentserver/");

        String resolved = service.resolveDocumentServerDownloadUrl(
                "http://localhost:8082/cache/files/data/edit/output.docx/output.docx?md5=abc");

        assertThat(resolved)
                .isEqualTo("http://onlyoffice-documentserver/cache/files/data/edit/output.docx/output.docx?md5=abc");
    }

    @Test
    void resolveDocumentServerDownloadUrl_shouldRewriteAndroidEmulatorAlias() {
        properties.setInternalUrl("http://onlyoffice-documentserver/");

        String resolved = service.resolveDocumentServerDownloadUrl(
                "http://10.0.2.2:8082/cache/files/data/edit/output.docx/output.docx?md5=abc");

        assertThat(resolved)
                .isEqualTo("http://onlyoffice-documentserver/cache/files/data/edit/output.docx/output.docx?md5=abc");
    }

    @Test
    void resolveDocumentServerDownloadUrl_shouldNotRewriteDifferentLocalPort() {
        properties.setInternalUrl("http://onlyoffice-documentserver/");

        String resolved = service.resolveDocumentServerDownloadUrl(
                "http://10.0.2.2:8083/cache/files/data/edit/output.docx/output.docx?md5=abc");

        assertThat(resolved)
                .isEqualTo("http://10.0.2.2:8083/cache/files/data/edit/output.docx/output.docx?md5=abc");
    }

    private FileEntity officeFile(String fileName) {
        return FileEntity.builder()
                .id(7L)
                .userId(1L)
                .fileName(fileName)
                .fileType("file")
                .fileSize(10L)
                .storagePath("path")
                .md5Hash("md5")
                .versionCount(1)
                .updatedAt(LocalDateTime.parse("2026-06-28T10:00:00"))
                .build();
    }

    @SuppressWarnings("unchecked")
    private void assertPluginsDisabled(Map<String, Object> editorConfig) {
        Map<String, Object> plugins = (Map<String, Object>) editorConfig.get("plugins");
        assertThat(plugins).isNotNull();
        assertThat((Iterable<?>) plugins.get("autostart")).isEmpty();
        assertThat((Iterable<?>) plugins.get("pluginsData")).isEmpty();
        List<Object> disabledPlugins = new ArrayList<>();
        ((Iterable<?>) plugins.get("disable")).forEach(disabledPlugins::add);
        assertThat(disabledPlugins).contains(ONLYOFFICE_AI_PLUGIN_GUID);
    }
}
