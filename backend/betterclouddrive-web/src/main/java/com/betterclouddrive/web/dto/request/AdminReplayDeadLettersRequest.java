package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record AdminReplayDeadLettersRequest(
        @NotEmpty @Size(max = 20) List<UUID> ids) {
}
