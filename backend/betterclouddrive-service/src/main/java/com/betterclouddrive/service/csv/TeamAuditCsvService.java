package com.betterclouddrive.service.csv;

import com.betterclouddrive.dal.entity.TeamOperationLogEntity;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class TeamAuditCsvService {

    private static final DateTimeFormatter EXPORT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String TEAM_AUDIT_HEADER = "createdAt,teamId,actorUserId,action,targetType,targetId,result,statusCode,traceId,details";

    private final Clock clock;

    public TeamAuditCsvService() {
        this(Clock.systemDefaultZone());
    }

    TeamAuditCsvService(Clock clock) {
        this.clock = clock;
    }

    public String teamAuditFileName(Long teamId) {
        return "team-" + teamId + "-audit-" + LocalDateTime.now(clock).format(EXPORT_TIME_FORMAT) + ".csv";
    }

    public String teamAuditCsv(List<TeamOperationLogEntity> logs) {
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

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }
}
