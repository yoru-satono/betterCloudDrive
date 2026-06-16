package com.betterclouddrive.web.dto.request;

import lombok.Data;

@Data
public class UpdateWebDavSettingsRequest {
    @jakarta.validation.constraints.NotNull
    private Boolean enabled;
    private String password;
}
