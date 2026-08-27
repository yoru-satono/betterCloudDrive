package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ReplaceFileTagsRequest {
    @NotNull
    private List<@NotNull Long> tagIds;
}
