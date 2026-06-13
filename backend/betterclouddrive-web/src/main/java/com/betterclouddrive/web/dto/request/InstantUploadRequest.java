package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class InstantUploadRequest {
    private Long parentId;
    @NotBlank
    private String fileName;
    @NotNull @Positive
    private Long fileSize;
    @NotBlank
    private String md5Hash;
}
