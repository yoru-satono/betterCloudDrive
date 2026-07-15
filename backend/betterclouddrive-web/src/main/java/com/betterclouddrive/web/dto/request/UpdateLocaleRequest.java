package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateLocaleRequest {
    @NotBlank
    @Pattern(regexp = "^(zh-CN|en-US)$", message = "Locale must be zh-CN or en-US")
    private String preferredLocale;
}
