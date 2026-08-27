package com.betterclouddrive.web.service;

import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.service.i18n.LocaleCatalog;
import com.betterclouddrive.web.observability.ObservabilityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GrafanaLocaleSyncServiceTest {

    private ObservabilityProperties properties;
    private MockRestServiceServer server;
    private GrafanaLocaleSyncService service;

    @BeforeEach
    void setUp() {
        properties = new ObservabilityProperties();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://grafana.test");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new GrafanaLocaleSyncService(
                properties,
                new LocaleCatalog("zh-Hans"),
                builder.build());
    }

    @Test
    void mapsApplicationLocalesToGrafanaLocales() {
        assertThat(service.grafanaLocale("zh-Hans")).isEqualTo("zh-Hans");
        assertThat(service.grafanaLocale("zh-CN")).isEqualTo("zh-Hans");
        assertThat(service.grafanaLocale("zh-SG")).isEqualTo("zh-Hans");
        assertThat(service.grafanaLocale("en-US")).isEqualTo("en-US");
        assertThat(service.grafanaLocale(null)).isEqualTo("zh-Hans");
    }

    @Test
    void synchronizesOnlyTheLanguagePreferenceForAdmin() {
        server.expect(requestTo("http://grafana.test/api/user/preferences"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header(GrafanaAuthService.HEADER_USER, "admin"))
                .andExpect(header(GrafanaAuthService.HEADER_NAME, "Administrator"))
                .andExpect(header(GrafanaAuthService.HEADER_EMAIL, "admin@test.local"))
                .andExpect(header(GrafanaAuthService.HEADER_ROLE, "Admin"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"language\":\"zh-Hans\"}"))
                .andRespond(withSuccess());

        service.syncIfAdmin(admin());

        server.verify();
    }

    @Test
    void grafanaFailureDoesNotEscapeTheSynchronizationBoundary() {
        server.expect(requestTo("http://grafana.test/api/user/preferences"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

        assertThatCode(() -> service.syncIfAdmin(admin())).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void skipsNonAdminAndDisabledSynchronization() {
        UserEntity user = admin();
        user.setRole("ROLE_USER");
        service.syncIfAdmin(user);

        properties.getGrafanaAuth().setLocaleSyncEnabled(false);
        user.setRole("ROLE_ADMIN");
        service.syncIfAdmin(user);

        server.verify();
    }

    private UserEntity admin() {
        return UserEntity.builder()
                .id(2L)
                .username("admin")
                .nickname("Administrator")
                .email("admin@test.local")
                .preferredLocale("zh-Hans")
                .role("ROLE_ADMIN")
                .status(1)
                .build();
    }
}
