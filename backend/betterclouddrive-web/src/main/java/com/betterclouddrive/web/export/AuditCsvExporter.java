package com.betterclouddrive.web.export;

import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import com.betterclouddrive.service.csv.TeamAuditCsvService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AuditCsvExporter {

    private final TeamAuditCsvService teamAuditCsvService;

    public ResponseEntity<String> teamAuditResponse(Long teamId, List<TeamOperationLogEntity> logs) {
        return csvResponse(teamAuditCsvService.teamAuditFileName(teamId), teamAuditCsvService.teamAuditCsv(logs));
    }

    private ResponseEntity<String> csvResponse(String fileName, String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(body);
    }
}
