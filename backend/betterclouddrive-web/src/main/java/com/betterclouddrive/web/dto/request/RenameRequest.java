package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RenameRequest {
    @NotBlank
    private String newName;
}
