package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TransferTeamOwnerRequest {
    @NotNull
    private Long userId;
}
