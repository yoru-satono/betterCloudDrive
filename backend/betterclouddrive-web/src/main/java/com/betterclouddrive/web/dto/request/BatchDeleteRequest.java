package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BatchDeleteRequest {
    @NotEmpty
    private List<Long> fileIds;
}
