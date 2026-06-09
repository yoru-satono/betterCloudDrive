package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VerifyEmailRequest {
    @NotBlank
    @Size(min = 6, max = 6, message = "Verification code must be 6 digits")
    private String code;
}
