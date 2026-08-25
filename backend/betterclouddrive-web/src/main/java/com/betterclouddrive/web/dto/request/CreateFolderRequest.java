package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateFolderRequest {
    private Long parentId;
    @NotBlank
    @Size(max = 255)
    private String folderName;
}
