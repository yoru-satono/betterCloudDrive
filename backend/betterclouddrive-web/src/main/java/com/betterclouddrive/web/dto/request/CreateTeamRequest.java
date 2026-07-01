package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTeamRequest {

    @NotBlank
    @Size(min = 1, max = 80)
    private String name;
}
