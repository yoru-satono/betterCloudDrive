package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateLocaleRequest {
    @NotBlank
    @Size(max = 35)
    private String preferredLocale;
}
