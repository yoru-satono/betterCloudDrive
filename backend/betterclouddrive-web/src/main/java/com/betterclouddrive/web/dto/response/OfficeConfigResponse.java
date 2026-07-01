package com.betterclouddrive.web.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class OfficeConfigResponse {
    private String documentServerUrl;
    private boolean editable;
    private Map<String, Object> config;
}
