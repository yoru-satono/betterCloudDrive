package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class InitUploadRequest {
    private Long parentId;
    @NotBlank
    private String fileName;
    @NotNull @PositiveOrZero
    private Long fileSize;
    private String md5Hash;
    @PositiveOrZero
    private int totalChunks;
}
