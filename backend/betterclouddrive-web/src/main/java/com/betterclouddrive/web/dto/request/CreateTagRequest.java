package com.betterclouddrive.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateTagRequest {
    @NotBlank
    private String tagName;
    private String color;
}
