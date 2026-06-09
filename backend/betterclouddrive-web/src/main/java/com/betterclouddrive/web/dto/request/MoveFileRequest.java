package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MoveFileRequest {
    @NotNull
    private Long targetParentId;
}
