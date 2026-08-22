package com.betterclouddrive.web.controller;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.UserEntity;
import com.betterclouddrive.service.AuthService;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.OnlyOfficeCallbackService;
import com.betterclouddrive.service.TeamPermissionService;
import com.betterclouddrive.service.TeamTaskService;
import com.betterclouddrive.service.dto.TeamEffectivePermission;
import com.betterclouddrive.service.metrics.OnlyOfficeSaveMetrics;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.web.dto.response.OfficeConfigResponse;
import com.betterclouddrive.web.security.UserPrincipal;
import com.betterclouddrive.web.service.OnlyOfficeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfficeControllerConfigTest {

    @Mock private FileService fileService;
    @Mock private AuthService authService;
    @Mock private StorageService storageService;
    @Mock private OnlyOfficeService onlyOfficeService;
    @Mock private OnlyOfficeCallbackService onlyOfficeCallbackService;
    @Mock private TeamPermissionService teamPermissionService;
    @Mock private TeamTaskService teamTaskService;
    @Mock private OnlyOfficeSaveMetrics onlyOfficeSaveMetrics;

    private OfficeController controller;
    private final UserPrincipal user = new UserPrincipal(2L, "stage0", "jti", "ROLE_USER");
    private final FileEntity file = FileEntity.builder().id(7L).fileName("document.docx").build();

    @BeforeEach
    void setUp() {
        controller = new OfficeController(
                fileService,
                authService,
                storageService,
                onlyOfficeService,
                onlyOfficeCallbackService,
                teamPermissionService,
                teamTaskService,
                onlyOfficeSaveMetrics);
        when(fileService.getTeamFile(10L, 7L)).thenReturn(file);
        when(onlyOfficeService.isOfficeFile(file)).thenReturn(true);
        when(teamPermissionService.effectivePermissions(2L, 10L, 7L))
                .thenReturn(TeamEffectivePermission.editor());
        when(authService.getCurrentUser(2L))
                .thenReturn(UserEntity.builder().id(2L).preferredLocale("zh-Hans").build());
    }

    @Test
    void teamConfig_shouldForwardMobilePolicyForWritableMember() {
        OfficeConfigResponse mobileResponse = OfficeConfigResponse.builder()
                .editable(false)
                .config(Map.of("type", "mobile"))
                .build();
        when(onlyOfficeService.buildTeamConfig(2L, 10L, file, true, "zh-Hans", true))
                .thenReturn(mobileResponse);

        var response = controller.teamConfig(user, 10L, 7L, true);

        assertThat(response.getData().isEditable()).isFalse();
        verify(onlyOfficeService).buildTeamConfig(2L, 10L, file, true, "zh-Hans", true);
    }

    @Test
    void teamConfig_shouldKeepDesktopEditingByDefault() {
        OfficeConfigResponse desktopResponse = OfficeConfigResponse.builder()
                .editable(true)
                .config(Map.of("type", "desktop"))
                .build();
        when(onlyOfficeService.buildTeamConfig(2L, 10L, file, true, "zh-Hans", false))
                .thenReturn(desktopResponse);

        var response = controller.teamConfig(user, 10L, 7L, false);

        assertThat(response.getData().isEditable()).isTrue();
        verify(onlyOfficeService).buildTeamConfig(2L, 10L, file, true, "zh-Hans", false);
    }
}
