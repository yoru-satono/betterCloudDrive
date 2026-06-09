package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class InitUploadRequest {
    @NotNull
    private Long parentId;
    @NotBlank
    private String fileName;
    @NotNull @Positive
    private Long fileSize;
    private String md5Hash;
    @NotNull @Positive
    private int totalChunks;
}
