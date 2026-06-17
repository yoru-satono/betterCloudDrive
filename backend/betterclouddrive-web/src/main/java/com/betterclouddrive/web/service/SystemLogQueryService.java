package com.betterclouddrive.web.service;

import com.betterclouddrive.web.dto.response.SystemLogEntryResponse;

import java.time.Instant;
import java.util.List;

public interface SystemLogQueryService {
    List<SystemLogEntryResponse> listSystemLogs(String traceId, String requestId, String level, String logType,
                                                String keyword, Instant startTime, Instant endTime, Integer limit);
}
