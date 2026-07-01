package com.betterclouddrive.web.export;

import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class AuditCsvExporter {

    private static final DateTimeFormatter EXPORT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String TEAM_AUDIT_HEADER = "createdAt,teamId,actorUserId,action,targetType,targetId,result,statusCode,traceId,details";

    private final Clock clock;

    public AuditCsvExporter() {
        this(Clock.systemDefaultZone());
    }

    AuditCsvExporter(Clock clock) {
        this.clock = clock;
    }

    public ResponseEntity<String> teamAuditResponse(Long teamId, List<TeamOperationLogEntity> logs) {
        return csvResponse(teamAuditFileName(teamId), teamAuditCsv(logs));
    }

    String teamAuditFileName(Long teamId) {
        return "team-" + teamId + "-audit-" + LocalDateTime.now(clock).format(EXPORT_TIME_FORMAT) + ".csv";
    }

    String teamAuditCsv(List<TeamOperationLogEntity> logs) {
        List<String> rows = new ArrayList<>();
        rows.add(TEAM_AUDIT_HEADER);
        logs.forEach(log -> rows.add(String.join(",",
                csv(log.getCreatedAt()),
                csv(log.getTeamId()),
                csv(log.getActorUserId()),
                csv(log.getAction()),
                csv(log.getTargetType()),
                csv(log.getTargetId()),
                csv(log.getResult()),
                csv(log.getStatusCode()),
                csv(log.getTraceId()),
                csv(log.getDetails()))));
        return String.join("\n", rows) + "\n";
    }

    private ResponseEntity<String> csvResponse(String fileName, String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(body);
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }
}
