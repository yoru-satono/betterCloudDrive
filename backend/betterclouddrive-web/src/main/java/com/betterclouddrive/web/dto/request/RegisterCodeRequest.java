package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RegisterCodeRequest {
    @NotBlank
    @Email
    private String email;
}
