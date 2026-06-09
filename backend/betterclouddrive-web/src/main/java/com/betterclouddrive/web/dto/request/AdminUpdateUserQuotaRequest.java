package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class AdminUpdateUserQuotaRequest {
    @NotNull
    @Positive
    private Long storageQuota; // bytes
}
