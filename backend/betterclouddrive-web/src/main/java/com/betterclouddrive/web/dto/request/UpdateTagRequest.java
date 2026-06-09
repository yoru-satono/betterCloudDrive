package com.betterclouddrive.web.dto.request;

import lombok.Data;

@Data
public class UpdateTagRequest {
    private String tagName;
    private String color;
}
