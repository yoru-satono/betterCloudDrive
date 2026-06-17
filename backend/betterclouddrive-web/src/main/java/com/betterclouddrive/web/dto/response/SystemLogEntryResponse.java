package com.betterclouddrive.web.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemLogEntryResponse {
    private String id;
    private String idType;
    private String traceId;
    private String requestId;
    private String timestamp;
    private String level;
    private String logger;
    private String message;
    private String path;
    private String method;
    private String logType;
    private String grafanaUrl;
}
