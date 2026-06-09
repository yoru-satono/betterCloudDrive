package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateFolderRequest {
    private Long parentId;
    @NotBlank
    private String folderName;
}
