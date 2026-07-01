package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AddTeamMemberRequest {

    @NotBlank
    @Size(min = 3, max = 64)
    private String username;

    @NotBlank
    private String role;
}
