package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import jakarta.validation.constraints.Size;

@Data
public class RegisterCodeRequest {
    @NotBlank
    @Email
    private String email;

    @Size(max = 35)
    private String preferredLocale;
}
