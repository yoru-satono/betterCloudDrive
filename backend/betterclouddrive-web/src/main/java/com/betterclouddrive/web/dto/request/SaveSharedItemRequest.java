package com.betterclouddrive.web.dto.request;

import lombok.Data;

@Data
public class SaveSharedItemRequest {
    private Long fileId;
    private Long targetParentId;
    private String password;
}
