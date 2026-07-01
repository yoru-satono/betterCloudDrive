package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateTeamInviteRequest {

    @Email
    @NotBlank
    private String email;

    @NotBlank
    private String role;
}
