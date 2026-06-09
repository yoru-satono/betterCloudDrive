package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminUpdateUserStatusRequest {
    @NotNull
    private Integer status; // 1=active, 0=disabled
}
