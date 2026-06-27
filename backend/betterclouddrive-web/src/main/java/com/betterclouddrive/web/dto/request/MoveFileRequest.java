package com.betterclouddrive.web.dto.request;

import lombok.Data;

@Data
public class MoveFileRequest {
    private Long targetParentId;
}
