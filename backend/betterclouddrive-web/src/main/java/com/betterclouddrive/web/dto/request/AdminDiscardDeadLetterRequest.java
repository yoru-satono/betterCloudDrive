package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminDiscardDeadLetterRequest(
        @NotBlank @Size(min = 3, max = 500) String reason) {
}
